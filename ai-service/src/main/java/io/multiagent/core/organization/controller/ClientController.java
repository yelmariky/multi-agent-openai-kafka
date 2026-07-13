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

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/clients")
public class ClientController {

    private final ClientRepository clientRepository;
    private final OrganizationRepository organizationRepository;

    @GetMapping
    public List<Client> list() {
        return clientRepository.findByTenantIdAndActiveTrue(TenantContext.getTenantId());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Client> create(@RequestBody Client client) {
        Organization tenant = organizationRepository.getReferenceById(TenantContext.getTenantId());
        client.setTenant(tenant);
        return ResponseEntity.ok(clientRepository.save(client));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable UUID id, @RequestBody Client updates) {
        UUID tenantId = TenantContext.getTenantId();
        Client existing = clientRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + id));
        if (updates.getName() != null && !updates.getName().equals(existing.getName())) {
            boolean nameConflict = clientRepository.findByTenantIdAndName(tenantId, updates.getName())
                    .filter(c -> !c.getId().equals(id))
                    .isPresent();
            if (nameConflict) {
                return ResponseEntity.status(409)
                        .body("Un client avec le nom \"" + updates.getName() + "\" existe déjà.");
            }
            existing.setName(updates.getName());
        }
        if (updates.getAddress() != null) existing.setAddress(updates.getAddress());
        if (updates.getRcs() != null) existing.setRcs(updates.getRcs());
        if (updates.getContactName() != null) existing.setContactName(updates.getContactName());
        if (updates.getContactEmail() != null) existing.setContactEmail(updates.getContactEmail());
        return ResponseEntity.ok(clientRepository.save(existing));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        Client client = clientRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + id));
        client.setActive(false);
        clientRepository.save(client);
        return ResponseEntity.noContent().build();
    }
}
