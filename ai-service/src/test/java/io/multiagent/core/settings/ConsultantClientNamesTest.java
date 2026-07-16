package io.multiagent.core.settings;

import io.multiagent.core.infrastructure.tenant.TenantContext;
import io.multiagent.core.organization.service.keycloak.KeycloakProvisioningService;
import io.multiagent.core.service.WeaviateService;
import io.multiagent.core.settings.controller.ConsultantController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConsultantController — clients par consultant (cartes admin)")
class ConsultantClientNamesTest {

    @Mock WeaviateService weaviateService;
    @Mock KeycloakProvisioningService keycloakService;
    @Mock JdbcTemplate jdbcTemplate;

    @InjectMocks ConsultantController controller;

    @BeforeEach
    void setUp() {
        TenantContext.set(UUID.randomUUID(), "ia-insight");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("plusieurs affectations → tous les clients du consultant, dédupliqués, ordre conservé")
    void groupsAllClientsByConsultantEmail() {
        when(jdbcTemplate.queryForList(anyString(), any(UUID.class))).thenReturn(List.of(
                Map.of("email", "albert@test.fr", "name", "INFOGENE DIGTAL"),
                Map.of("email", "albert@test.fr", "name", "ACME"),
                Map.of("email", "albert@test.fr", "name", "INFOGENE DIGTAL"),   // doublon (2 projets même client)
                Map.of("email", "bob@test.fr", "name", "ACME")
        ));

        Map<String, List<String>> body = controller.clientNamesByConsultant().getBody();

        assertThat(body).containsEntry("albert@test.fr", List.of("INFOGENE DIGTAL", "ACME"));
        assertThat(body).containsEntry("bob@test.fr", List.of("ACME"));
    }

    @Test
    @DisplayName("aucune affectation → map vide (les cartes replient sur clientName)")
    void emptyWhenNoAssignments() {
        when(jdbcTemplate.queryForList(anyString(), any(UUID.class))).thenReturn(List.of());
        assertThat(controller.clientNamesByConsultant().getBody()).isEmpty();
    }
}
