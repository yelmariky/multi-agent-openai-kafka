package io.multiagent.core.organization;

import io.multiagent.core.infrastructure.tenant.TenantContext;
import io.multiagent.core.organization.controller.ClientController;
import io.multiagent.core.organization.entity.Client;
import io.multiagent.core.organization.repository.ClientRepository;
import io.multiagent.core.organization.repository.OrganizationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClientController — renommage et conflits de nom")
class ClientControllerTest {

    @Mock ClientRepository clientRepository;
    @Mock OrganizationRepository organizationRepository;
    @Mock org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @InjectMocks ClientController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID clientId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.set(tenantId, "ia-insight");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Client client(UUID id, String name, boolean active) {
        Client c = new Client();
        c.setId(id);
        c.setName(name);
        c.setActive(active);
        return c;
    }

    @Test
    @DisplayName("conflit avec un client ARCHIVÉ → message explicite mentionnant l'archive")
    void archivedConflictGivesExplicitMessage() {
        when(clientRepository.findById(clientId)).thenReturn(Optional.of(client(clientId, "INFOGENE DIGTAL", true)));
        when(clientRepository.findByTenantIdAndName(tenantId, "INFOGENE DIGITAL"))
                .thenReturn(Optional.of(client(UUID.randomUUID(), "INFOGENE DIGITAL", false)));  // archivé

        ResponseEntity<?> res = controller.update(clientId, client(null, "INFOGENE DIGITAL", true));

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat((String) res.getBody()).contains("archivé").contains("INFOGENE DIGITAL");
    }

    @Test
    @DisplayName("conflit avec un client ACTIF → message standard")
    void activeConflictGivesStandardMessage() {
        when(clientRepository.findById(clientId)).thenReturn(Optional.of(client(clientId, "ACME", true)));
        when(clientRepository.findByTenantIdAndName(tenantId, "EDF"))
                .thenReturn(Optional.of(client(UUID.randomUUID(), "EDF", true)));

        ResponseEntity<?> res = controller.update(clientId, client(null, "EDF", true));

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat((String) res.getBody()).contains("actif").doesNotContain("archivé");
    }

    @Test
    @DisplayName("nom libre → renommage accepté")
    void renameSucceedsWhenNameFree() {
        when(clientRepository.findById(clientId)).thenReturn(Optional.of(client(clientId, "INFOGENE DIGTAL", true)));
        when(clientRepository.findByTenantIdAndName(tenantId, "INFOGENE DIGITAL")).thenReturn(Optional.empty());
        when(clientRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> res = controller.update(clientId, client(null, "INFOGENE DIGITAL", true));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(((Client) res.getBody()).getName()).isEqualTo("INFOGENE DIGITAL");
    }

    @Test
    @DisplayName("list(includeInactive=true) → renvoie tous les clients ; false → actifs seulement")
    void listRespectsIncludeInactiveFlag() {
        when(clientRepository.findByTenantId(tenantId)).thenReturn(List.of(client(clientId, "A", true)));
        when(clientRepository.findByTenantIdAndActiveTrue(tenantId)).thenReturn(List.of());

        assertThat(controller.list(true)).hasSize(1);
        assertThat(controller.list(false)).isEmpty();
    }

    @Test
    @DisplayName("réactivation d'un client archivé → active=true")
    void reactivateSucceeds() {
        when(clientRepository.findById(clientId)).thenReturn(Optional.of(client(clientId, "ACME", false)));
        when(clientRepository.findByTenantIdAndName(tenantId, "ACME")).thenReturn(Optional.empty());
        when(clientRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> res = controller.reactivate(clientId);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(((Client) res.getBody()).isActive()).isTrue();
    }

    @Test
    @DisplayName("archivage bloqué si un consultant ACTIF est rattaché")
    void archiveBlockedByActiveConsultant() {
        when(clientRepository.findById(clientId)).thenReturn(Optional.of(client(clientId, "ACME", true)));
        when(jdbcTemplate.queryForList(any(String.class), eq(String.class), any(), any(), any()))
                .thenReturn(List.of("Alice Martin"));

        ResponseEntity<?> res = controller.delete(clientId);

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat((String) res.getBody()).contains("Alice Martin").contains("ACME");
    }

    @Test
    @DisplayName("archivage autorisé si aucun consultant actif (tous inactifs ou aucun)")
    void archiveSucceedsWhenNoActiveConsultant() {
        when(clientRepository.findById(clientId)).thenReturn(Optional.of(client(clientId, "ACME", true)));
        when(jdbcTemplate.queryForList(any(String.class), eq(String.class), any(), any(), any()))
                .thenReturn(List.of());
        when(clientRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> res = controller.delete(clientId);

        assertThat(res.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    @DisplayName("réactivation bloquée si un client actif porte déjà le même nom")
    void reactivateBlockedByActiveNamesake() {
        when(clientRepository.findById(clientId)).thenReturn(Optional.of(client(clientId, "ACME", false)));
        when(clientRepository.findByTenantIdAndName(tenantId, "ACME"))
                .thenReturn(Optional.of(client(UUID.randomUUID(), "ACME", true)));

        ResponseEntity<?> res = controller.reactivate(clientId);

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat((String) res.getBody()).contains("actif").contains("ACME");
    }
}
