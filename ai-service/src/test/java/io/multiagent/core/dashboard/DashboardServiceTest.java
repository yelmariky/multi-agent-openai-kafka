package io.multiagent.core.dashboard;

import io.multiagent.core.cra.entity.CraEntity;
import io.multiagent.core.cra.repository.CraJpaRepository;
import io.multiagent.core.dashboard.model.DashboardSummary;
import io.multiagent.core.dashboard.repository.InvoiceDashboardRepository;
import io.multiagent.core.dashboard.service.DashboardService;
import io.multiagent.core.expense.repository.ExpenseJpaRepository;
import io.multiagent.core.infrastructure.tenant.TenantContext;
import io.multiagent.core.organization.repository.ConsultantAssignmentRepository;
import io.multiagent.core.settings.entity.ConsultantProfileEntity;
import io.multiagent.core.settings.repository.ConsultantProfileJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardService — KPIs et marge")
class DashboardServiceTest {

    @Mock ConsultantProfileJpaRepository  consultantProfileRepo;
    @Mock ConsultantAssignmentRepository  assignmentRepo;
    @Mock CraJpaRepository                craRepo;
    @Mock ExpenseJpaRepository            expenseRepo;
    @Mock InvoiceDashboardRepository      invoiceRepo;

    @InjectMocks DashboardService service;

    private static final UUID TENANT = UUID.randomUUID();

    @Test
    @DisplayName("Marge calculée uniquement pour les consultants avec daily_cost")
    void marge_calculee_seulement_si_daily_cost_renseigne() {
        ConsultantProfileEntity avecCout   = profile("alice@test.com", 600.0, 350.0);
        ConsultantProfileEntity sansCout   = profile("bob@test.com",   500.0, null);

        try (MockedStatic<TenantContext> tc = mockStatic(TenantContext.class)) {
            tc.when(TenantContext::getTenantId).thenReturn(TENANT);
            when(consultantProfileRepo.findByTenantIdAndActiveTrue(TENANT))
                .thenReturn(List.of(avecCout, sansCout));
            when(assignmentRepo.findByConsultantProfileIdAndTenantId(any(), eq(TENANT)))
                .thenReturn(List.of());
            when(craRepo.findByTenantIdAndConsultantIgnoreCaseAndBillingMonth(any(), any(), any()))
                .thenReturn(Optional.empty());
            when(expenseRepo.findByTenantIdAndApprovalStatus(eq(TENANT), eq("PENDING")))
                .thenReturn(List.of());
            when(invoiceRepo.findOverdue(eq(TENANT), any())).thenReturn(List.of());

            DashboardSummary summary = service.summary("2026-06");

            // Marge globale = seulement alice (350€/j × 0j = 0, mais nbAvecCout=1)
            assertThat(summary.getNbConsultantsAvecCout()).isEqualTo(1);
            // bob n'a pas de daily_cost → sa row a coutTotal=null, margeNette=null
            DashboardSummary.ConsultantRow bobRow = summary.getConsultants().stream()
                .filter(r -> r.getEmail().equals("bob@test.com"))
                .findFirst().orElseThrow();
            assertThat(bobRow.getDailyCost()).isNull();
            assertThat(bobRow.getCoutTotal()).isNull();
            assertThat(bobRow.getMargeNette()).isNull();
            assertThat(bobRow.getTauxMarge()).isNull();
        }
    }

    @Test
    @DisplayName("CA et marge corrects quand CRA validé avec jours")
    void ca_et_marge_corrects_avec_cra_valide() {
        ConsultantProfileEntity alice = profile("alice@test.com", 600.0, 350.0);
        CraEntity cra = new CraEntity();
        cra.setStatus("VALIDE");
        cra.setTotalDays(BigDecimal.valueOf(20));

        try (MockedStatic<TenantContext> tc = mockStatic(TenantContext.class)) {
            tc.when(TenantContext::getTenantId).thenReturn(TENANT);
            when(consultantProfileRepo.findByTenantIdAndActiveTrue(TENANT))
                .thenReturn(List.of(alice));
            when(assignmentRepo.findByConsultantProfileIdAndTenantId(any(), eq(TENANT)))
                .thenReturn(List.of());
            when(craRepo.findByTenantIdAndConsultantIgnoreCaseAndBillingMonth(any(), any(), any()))
                .thenReturn(Optional.of(cra));
            when(expenseRepo.findByTenantIdAndApprovalStatus(any(), any()))
                .thenReturn(List.of());
            when(invoiceRepo.findOverdue(any(), any())).thenReturn(List.of());

            DashboardSummary summary = service.summary("2026-06");

            DashboardSummary.ConsultantRow row = summary.getConsultants().get(0);
            // CA = tjm(600) × jours(20) = 12 000 (TJM du profil car pas d'assignment)
            // Mais TJM vient du profil = 600 (setTjm dans profile)
            assertThat(row.getCaFacturable()).isEqualTo(12_000.0);
            assertThat(row.getCoutTotal()).isEqualTo(7_000.0);  // 350 × 20
            assertThat(row.getMargeNette()).isEqualTo(5_000.0); // 12000 - 7000
            assertThat(row.getTauxMarge()).isCloseTo(41.7, org.assertj.core.data.Offset.offset(0.1));
        }
    }

    @Test
    @DisplayName("Personnel interne (isConsultant=false) exclu du dashboard")
    void admin_exclu_du_dashboard() {
        ConsultantProfileEntity admin = profile("admin@test.com", 0.0, null);
        admin.setIsConsultant(false);

        try (MockedStatic<TenantContext> tc = mockStatic(TenantContext.class)) {
            tc.when(TenantContext::getTenantId).thenReturn(TENANT);
            when(consultantProfileRepo.findByTenantIdAndActiveTrue(TENANT))
                .thenReturn(List.of(admin));
            when(expenseRepo.findByTenantIdAndApprovalStatus(any(), any()))
                .thenReturn(List.of());
            when(invoiceRepo.findOverdue(any(), any())).thenReturn(List.of());

            DashboardSummary summary = service.summary("2026-06");
            assertThat(summary.getConsultants()).isEmpty();
        }
    }

    // --- helper ---
    private ConsultantProfileEntity profile(String email, double tjm, Double dailyCost) {
        ConsultantProfileEntity p = new ConsultantProfileEntity();
        p.setId(UUID.randomUUID());
        p.setEmail(email);
        p.setName(email.split("@")[0]);
        p.setTjm(BigDecimal.valueOf(tjm));
        p.setActive(true);
        p.setIsConsultant(true);
        if (dailyCost != null) p.setDailyCost(BigDecimal.valueOf(dailyCost));
        return p;
    }
}
