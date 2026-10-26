package io.multiagent.core.policy.service;

import io.multiagent.core.infrastructure.tenant.TenantContext;
import io.multiagent.core.policy.entity.PolicyDocumentEntity;
import io.multiagent.core.policy.repository.PolicyDocumentJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicyIngestionServiceTest {

    private static final String EXPENSE_REIMBURSEMENT = "EXPENSE_REIMBURSEMENT";

    @Mock
    private PolicyDocumentJpaRepository policyRepo;
    @Mock
    private PolicyFileStorageService fileStorage;

    private PolicyIngestionService service;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        service = new PolicyIngestionService(policyRepo, fileStorage, io.multiagent.core.i18n.MessagesFixture.standard());
        tenantId = UUID.randomUUID();
        TenantContext.set(tenantId, "ia-insight");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static void stubSaveReturnsArgumentWithId(PolicyDocumentJpaRepository repo) {
        when(repo.save(any(PolicyDocumentEntity.class))).thenAnswer(inv -> {
            PolicyDocumentEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });
    }

    @Test
    void ingestCreatesFirstVersionAsDraftWhenNoneExists() {
        when(policyRepo.findTopByTenantIdAndPolicyTypeOrderByVersionDesc(tenantId, EXPENSE_REIMBURSEMENT))
                .thenReturn(Optional.empty());
        stubSaveReturnsArgumentWithId(policyRepo);

        PolicyDocumentEntity result = service.ingest(
                EXPENSE_REIMBURSEMENT, "Ma politique", "Les repas sont plafonnés à 40 euros.", "admin@ia-insight.com");

        assertThat(result.getVersion()).isEqualTo(1);
        assertThat(result.getStatus()).isEqualTo("DRAFT");
        assertThat(result.getTenantId()).isEqualTo(tenantId);
        assertThat(result.getRawText()).isEqualTo("Les repas sont plafonnés à 40 euros.");
    }

    @Test
    void ingestIncrementsVersionWhenPreviousExists() {
        PolicyDocumentEntity previous = new PolicyDocumentEntity();
        previous.setVersion(2);
        when(policyRepo.findTopByTenantIdAndPolicyTypeOrderByVersionDesc(tenantId, EXPENSE_REIMBURSEMENT))
                .thenReturn(Optional.of(previous));
        stubSaveReturnsArgumentWithId(policyRepo);

        PolicyDocumentEntity result = service.ingest(EXPENSE_REIMBURSEMENT, "v3", "Texte court.", "admin@ia-insight.com");

        assertThat(result.getVersion()).isEqualTo(3);
    }

    @Test
    void ingestPdfStoresOriginalFileAndSetsSourceFilePath() {
        when(policyRepo.findTopByTenantIdAndPolicyTypeOrderByVersionDesc(tenantId, EXPENSE_REIMBURSEMENT))
                .thenReturn(Optional.empty());
        stubSaveReturnsArgumentWithId(policyRepo);
        when(fileStorage.save(any(), anyString())).thenReturn("/tmp/policies/some-id.pdf");
        MockMultipartFile file = new MockMultipartFile("file", "politique.pdf", "application/pdf", new byte[]{1, 2, 3});

        PolicyDocumentEntity result = service.ingestPdf(
                EXPENSE_REIMBURSEMENT, "Politique PDF", "Texte extrait.", "admin@ia-insight.com", file);

        assertThat(result.getSourceFilePath()).isEqualTo("/tmp/policies/some-id.pdf");
        assertThat(result.getStatus()).isEqualTo("DRAFT");
    }

    @Test
    void ingestTextOnlyLeavesSourceFilePathNull() {
        when(policyRepo.findTopByTenantIdAndPolicyTypeOrderByVersionDesc(tenantId, EXPENSE_REIMBURSEMENT))
                .thenReturn(Optional.empty());
        stubSaveReturnsArgumentWithId(policyRepo);

        PolicyDocumentEntity result = service.ingest(EXPENSE_REIMBURSEMENT, "Texte", "Contenu.", "admin@ia-insight.com");

        assertThat(result.getSourceFilePath()).isNull();
    }

    @Test
    void activateThrowsWhenPolicyNotFoundForTenant() {
        UUID id = UUID.randomUUID();
        when(policyRepo.findByIdAndTenantId(id, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.activate(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("introuvable");
    }

    @Test
    void activateThrowsWhenTargetIsNotDraft() {
        UUID id = UUID.randomUUID();
        PolicyDocumentEntity target = new PolicyDocumentEntity();
        target.setId(id);
        target.setStatus("ARCHIVED");
        when(policyRepo.findByIdAndTenantId(id, tenantId)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.activate(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DRAFT");
    }

    @Test
    void activateArchivesPreviousActiveAndActivatesTarget() {
        UUID id = UUID.randomUUID();
        PolicyDocumentEntity target = new PolicyDocumentEntity();
        target.setId(id);
        target.setStatus("DRAFT");
        target.setPolicyType(EXPENSE_REIMBURSEMENT);
        when(policyRepo.findByIdAndTenantId(id, tenantId)).thenReturn(Optional.of(target));

        PolicyDocumentEntity previousActive = new PolicyDocumentEntity();
        previousActive.setId(UUID.randomUUID());
        previousActive.setStatus("ACTIVE");
        when(policyRepo.findByTenantIdAndPolicyTypeAndStatus(tenantId, EXPENSE_REIMBURSEMENT, "ACTIVE"))
                .thenReturn(Optional.of(previousActive));
        when(policyRepo.saveAndFlush(any(PolicyDocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(policyRepo.save(any(PolicyDocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyDocumentEntity result = service.activate(id);

        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        assertThat(result.getActivatedAt()).isNotNull();
        assertThat(previousActive.getStatus()).isEqualTo("ARCHIVED");
        // L'archivage doit être flushé immédiatement (pas juste save) pour ne jamais avoir deux
        // versions ACTIVE en même temps vis-à-vis de l'index unique partiel en base — voir le bug
        // réel détecté en test manuel (duplicate key uq_policy_document_active).
        verify(policyRepo).saveAndFlush(previousActive);
    }

    @Test
    void findActivePolicyDelegatesToRepositoryForCurrentTenant() {
        PolicyDocumentEntity active = new PolicyDocumentEntity();
        when(policyRepo.findByTenantIdAndPolicyTypeAndStatus(tenantId, EXPENSE_REIMBURSEMENT, "ACTIVE"))
                .thenReturn(Optional.of(active));

        Optional<PolicyDocumentEntity> result = service.findActivePolicy(EXPENSE_REIMBURSEMENT);

        assertThat(result).contains(active);
    }
}
