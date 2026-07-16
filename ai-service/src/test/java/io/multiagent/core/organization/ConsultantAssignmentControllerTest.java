package io.multiagent.core.organization;

import io.multiagent.core.infrastructure.tenant.TenantContext;
import io.multiagent.core.organization.controller.ConsultantAssignmentController;
import io.multiagent.core.organization.entity.Client;
import io.multiagent.core.organization.entity.ConsultantAssignmentEntity;
import io.multiagent.core.organization.entity.ProjectEntity;
import io.multiagent.core.organization.repository.ClientRepository;
import io.multiagent.core.organization.repository.ConsultantAssignmentRepository;
import io.multiagent.core.organization.repository.ProjectRepository;
import io.multiagent.core.settings.entity.ConsultantProfileEntity;
import io.multiagent.core.settings.repository.ConsultantProfileJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConsultantAssignmentController — synchronisation du client affiché sur la carte")
class ConsultantAssignmentControllerTest {

    @Mock ConsultantAssignmentRepository assignmentRepository;
    @Mock ConsultantProfileJpaRepository consultantProfileRepository;
    @Mock ProjectRepository projectRepository;
    @Mock ClientRepository clientRepository;

    @InjectMocks ConsultantAssignmentController controller;

    private final UUID consultantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID clientId = UUID.randomUUID();

    private ConsultantProfileEntity profile;
    private Client client;
    private ProjectEntity project;

    @BeforeEach
    void setUp() {
        TenantContext.set(UUID.randomUUID(), "ia-insight");
        profile = new ConsultantProfileEntity();
        profile.setClientName("INFOGEN TEST");   // texte libre obsolète saisi à la création
        client = new Client();
        client.setName("INFOGENE DIGTAL");       // affectation réelle
        project = new ProjectEntity();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private ConsultantAssignmentController.AssignmentRequest request() {
        return new ConsultantAssignmentController.AssignmentRequest(
                projectId, clientId, new BigDecimal("500"), null);
    }

    @Test
    @DisplayName("création d'affectation → clientName du profil aligné sur le client réel")
    void createSyncsProfileClientName() {
        when(consultantProfileRepository.findById(consultantId)).thenReturn(Optional.of(profile));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(clientRepository.findById(clientId)).thenReturn(Optional.of(client));
        when(assignmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        controller.create(consultantId, request());

        assertThat(profile.getClientName()).isEqualTo("INFOGENE DIGTAL");
        verify(consultantProfileRepository).save(profile);
    }

    @Test
    @DisplayName("modification d'affectation → clientName resynchronisé")
    void updateSyncsProfileClientName() {
        ConsultantAssignmentEntity existing = new ConsultantAssignmentEntity();
        existing.setConsultantProfile(profile);
        when(assignmentRepository.findByIdAndTenantId(any(), any())).thenReturn(Optional.of(existing));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(clientRepository.findById(clientId)).thenReturn(Optional.of(client));
        when(assignmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        controller.update(consultantId, UUID.randomUUID(), request());

        assertThat(profile.getClientName()).isEqualTo("INFOGENE DIGTAL");
        verify(consultantProfileRepository).save(profile);
    }

    @Test
    @DisplayName("clientName déjà à jour → pas d'écriture inutile du profil")
    void noProfileWriteWhenAlreadyInSync() {
        profile.setClientName("INFOGENE DIGTAL");
        when(consultantProfileRepository.findById(consultantId)).thenReturn(Optional.of(profile));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(clientRepository.findById(clientId)).thenReturn(Optional.of(client));
        when(assignmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        controller.create(consultantId, request());

        verify(consultantProfileRepository, never()).save(any());
    }
}
