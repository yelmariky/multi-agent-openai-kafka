package io.multiagent.core.organization.controller;

import io.multiagent.core.infrastructure.tenant.TenantContext;
import io.multiagent.core.organization.entity.Client;
import io.multiagent.core.organization.entity.Organization;
import io.multiagent.core.organization.repository.ClientRepository;
import io.multiagent.core.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@RequestMapping("/clients")
public class ClientController {

    private final ClientRepository clientRepository;
    private final OrganizationRepository organizationRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** Consultants ACTIFS rattachés à un client (via affectation ou champ texte hérité). */
    private List<String> activeConsultantsOnClient(UUID tenantId, String clientName) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT cp.name FROM consultant_profile cp
                WHERE cp.tenant_id = ? AND cp.active = TRUE AND (
                    LOWER(cp.client_name) = LOWER(?)
                    OR EXISTS (SELECT 1 FROM consultant_assignment ca
                               JOIN client c ON c.id = ca.client_id
                               WHERE ca.consultant_profile_id = cp.id AND LOWER(c.name) = LOWER(?)))
                """, String.class, tenantId, clientName, clientName);
    }

    @GetMapping
    public List<Client> list(@RequestParam(name = "includeInactive", defaultValue = "false") boolean includeInactive) {
        UUID tenantId = TenantContext.getTenantId();
        return includeInactive
                ? clientRepository.findByTenantId(tenantId)
                : clientRepository.findByTenantIdAndActiveTrue(tenantId);
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Client client) {
        UUID tenantId = TenantContext.getTenantId();
        boolean exists = clientRepository.findByTenantIdAndName(tenantId, client.getName()).isPresent();
        if (exists) {
            return ResponseEntity.status(409)
                    .body("Un client avec le nom \"" + client.getName() + "\" existe déjà.");
        }
        Organization tenant = organizationRepository.getReferenceById(tenantId);
        client.setTenant(tenant);
        return ResponseEntity.ok(clientRepository.save(client));
    }

    /** Supprime les doublons (même nom, même tenant) en gardant le plus ancien. */
    @PostMapping("/deduplicate")
    @Transactional
    public ResponseEntity<Map<String, Object>> deduplicate() {
        UUID tenantId = TenantContext.getTenantId();
        List<Client> all = clientRepository.findByTenantId(tenantId);
        Map<String, List<Client>> byName = all.stream()
                .collect(Collectors.groupingBy(c -> c.getName().trim().toLowerCase()));
        int removed = 0;
        for (List<Client> group : byName.values()) {
            if (group.size() <= 1) continue;
            group.sort(Comparator.comparing(Client::getCreatedAt));
            for (int i = 1; i < group.size(); i++) {
                clientRepository.delete(group.get(i));
                removed++;
            }
        }
        return ResponseEntity.ok(Map.of("duplicatesRemoved", removed));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable UUID id, @RequestBody Client updates) {
        UUID tenantId = TenantContext.getTenantId();
        Client existing = clientRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + id));
        if (updates.getName() != null && !updates.getName().equals(existing.getName())) {
            var conflict = clientRepository.findByTenantIdAndName(tenantId, updates.getName())
                    .filter(c -> !c.getId().equals(id));
            if (conflict.isPresent()) {
                boolean archived = !conflict.get().isActive();
                String msg = archived
                        ? "Un client archivé porte déjà le nom \"" + updates.getName() + "\". "
                          + "Réactivez-le ou renommez-le d'abord (bouton « Nettoyer doublons » pour fusionner)."
                        : "Un client actif porte déjà le nom \"" + updates.getName() + "\".";
                return ResponseEntity.status(409).body(msg);
            }
            existing.setName(updates.getName());
        }
        if (updates.getAddress() != null) existing.setAddress(updates.getAddress());
        if (updates.getRcs() != null) existing.setRcs(updates.getRcs());
        if (updates.getContactName() != null) existing.setContactName(updates.getContactName());
        if (updates.getContactEmail() != null) existing.setContactEmail(updates.getContactEmail());
        return ResponseEntity.ok(clientRepository.save(existing));
    }

    /** Archive un client (soft delete, réversible). Refusé si un consultant ACTIF y est rattaché. */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable UUID id) {
        UUID tenantId = TenantContext.getTenantId();
        Client client = clientRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + id));
        List<String> activeConsultants = activeConsultantsOnClient(tenantId, client.getName());
        if (!activeConsultants.isEmpty()) {
            return ResponseEntity.status(409)
                    .body("Impossible d'archiver \"" + client.getName() + "\" — consultant(s) actif(s) rattaché(s) : "
                          + String.join(", ", activeConsultants) + ".");
        }
        client.setActive(false);
        clientRepository.save(client);
        return ResponseEntity.noContent().build();
    }

    /** Réactive un client archivé — refusé si un client actif porte déjà le même nom. */
    @PutMapping("/{id}/reactivate")
    @Transactional
    public ResponseEntity<?> reactivate(@PathVariable UUID id) {
        UUID tenantId = TenantContext.getTenantId();
        Client client = clientRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + id));
        boolean nameTaken = clientRepository.findByTenantIdAndName(tenantId, client.getName())
                .filter(c -> !c.getId().equals(id) && c.isActive())
                .isPresent();
        if (nameTaken) {
            return ResponseEntity.status(409)
                    .body("Un client actif porte déjà le nom \"" + client.getName()
                          + "\". Renommez ou fusionnez avant de réactiver.");
        }
        client.setActive(true);
        return ResponseEntity.ok(clientRepository.save(client));
    }
}
