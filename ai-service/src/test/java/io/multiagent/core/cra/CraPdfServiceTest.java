package io.multiagent.core.cra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.multiagent.core.cra.entity.CraEntity;
import io.multiagent.core.cra.repository.CraJpaRepository;
import io.multiagent.core.cra.service.CraPdfService;
import io.multiagent.core.infrastructure.tenant.TenantContext;
import io.multiagent.core.organization.repository.ConsultantAssignmentRepository;
import io.multiagent.core.organization.repository.MissionRepository;
import io.multiagent.core.organization.repository.ProjectRepository;
import io.multiagent.core.service.WeaviateService;
import io.multiagent.core.settings.repository.ConsultantProfileJpaRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CraPdfService — accès au PDF selon statut et propriétaire")
class CraPdfServiceTest {

    private static final String OWNER = "consultant@ia-insight.fr";
    private static final UUID TENANT = UUID.randomUUID();

    @Mock private CraJpaRepository craRepo;
    @Mock private MissionRepository missionRepo;
    @Mock private ProjectRepository projectRepo;
    @Mock private ConsultantAssignmentRepository assignmentRepo;
    @Mock private WeaviateService weaviateService;
    @Mock private ConsultantProfileJpaRepository consultantProfileRepo;

    private CraPdfService service;

    @BeforeEach
    void setUp() {
        service = new CraPdfService(craRepo, missionRepo, projectRepo, assignmentRepo, weaviateService,
                new ObjectMapper(), consultantProfileRepo, io.multiagent.core.i18n.MessagesFixture.standard());
        TenantContext.set(TENANT, "ia-insight");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private UUID cra(String status) {
        CraEntity cra = new CraEntity();
        UUID id = UUID.randomUUID();
        cra.setId(id);
        cra.setTenantId(TENANT);
        cra.setConsultant(OWNER);
        cra.setStatus(status);
        cra.setBillingMonth("2026-10");
        cra.setEntriesJson("[]");
        when(craRepo.findById(id)).thenReturn(Optional.of(cra));
        return id;
    }

    private static String text(byte[] pdf) throws Exception {
        try (PDDocument doc = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void brouillon_pasDePdf() {
        UUID id = cra("BROUILLON");
        assertThatThrownBy(() -> service.generatePdf(id, null, OWNER, false))
                .isInstanceOf(CraPdfService.CraPdfNotAvailableException.class);
    }

    @Test
    void refuse_pasDePdf() {
        UUID id = cra("REFUSE");
        assertThatThrownBy(() -> service.generatePdf(id, null, OWNER, false))
                .isInstanceOf(CraPdfService.CraPdfNotAvailableException.class);
    }

    @Test
    void autreConsultant_refuse_avantMemeLeStatut() {
        UUID id = cra("BROUILLON");
        assertThatThrownBy(() -> service.generatePdf(id, null, "collegue@ia-insight.fr", false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void sansEmail_refuse() {
        UUID id = cra("VALIDE");
        assertThatThrownBy(() -> service.generatePdf(id, null, null, false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void soumis_proprietaire_pdfProvisoireSansSignature() throws Exception {
        UUID id = cra("SOUMIS");

        String content = text(service.generatePdf(id, null, "CONSULTANT@ia-insight.fr", false));

        assertThat(content).contains("PROVISOIRE").contains("Signature client après validation du CRA")
                .doesNotContain("Signature Client :");
    }

    @Test
    void valide_admin_pdfOfficielAvecSignature() throws Exception {
        UUID id = cra("VALIDE");

        String content = text(service.generatePdf(id, null, "admin@ia-insight.fr", true));

        assertThat(content).contains("Signature Client :").doesNotContain("PROVISOIRE");
    }

    @Test
    void autreTenant_introuvable() {
        UUID id = cra("VALIDE");
        TenantContext.set(UUID.randomUUID(), "autre");
        assertThatThrownBy(() -> service.generatePdf(id, null, OWNER, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
