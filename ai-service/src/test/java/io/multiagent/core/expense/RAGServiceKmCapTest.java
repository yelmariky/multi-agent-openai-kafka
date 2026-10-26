package io.multiagent.core.expense;

import io.multiagent.core.expense.entity.ExpenseEntity;
import io.multiagent.core.expense.repository.ExpenseJpaRepository;
import io.multiagent.core.expense.service.RAGService;
import io.multiagent.core.infrastructure.tenant.TenantContext;
import io.multiagent.core.model.ExpenseItem;
import io.multiagent.core.policy.service.PolicyComplianceService;
import io.multiagent.core.policy.service.PolicyComplianceService.KmDailyCap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plafond journalier des frais km issu de la politique ACTIVE du tenant (méthode privée
 * applyKmDailyCap testée par réflexion, comme RAGServiceKmTest).
 */
@DisplayName("RAGService — plafond journalier des frais km")
class RAGServiceKmCapTest {

    private static final String EMAIL = "consultant@ia-insight.fr";
    private static final KmDailyCap CAP_29 = new KmDailyCap(29.0, "les frais km ne doit pas dépasser 29 euros par jour");

    private RAGService svc;
    private PolicyComplianceService policy;
    private ExpenseJpaRepository repo;

    @BeforeEach
    void setUp() {
        svc = Mockito.mock(RAGService.class, Mockito.CALLS_REAL_METHODS);
        policy = Mockito.mock(PolicyComplianceService.class);
        repo = Mockito.mock(ExpenseJpaRepository.class);
        ReflectionTestUtils.setField(svc, "policyComplianceService", policy);
        ReflectionTestUtils.setField(svc, "expenseRepo", repo);
        ReflectionTestUtils.setField(svc, "messages", io.multiagent.core.i18n.MessagesFixture.standard());
        when(policy.kmCapVerdictJson(any(), anyDouble(), anyDouble(), anyDouble())).thenReturn("{\"compliant\":false}");
        TenantContext.set(UUID.randomUUID(), "ia-insight");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private List<ExpenseItem> applyCap(List<ExpenseItem> lines) throws Exception {
        Method m = RAGService.class.getDeclaredMethod("applyKmDailyCap", List.class);
        m.setAccessible(true);
        return (List<ExpenseItem>) m.invoke(svc, lines);
    }

    private static ExpenseItem kmLine(String date, double amount) {
        ExpenseItem e = new ExpenseItem();
        e.setType("frais_km");
        e.setDate(date);
        e.setAmount(amount);
        e.setConsultantEmail(EMAIL);
        return e;
    }

    private static ExpenseEntity existingKm(String date, double amount, String status) {
        ExpenseEntity e = new ExpenseEntity();
        e.setType("frais_km");
        e.setExpenseDate(LocalDate.parse(date));
        e.setAmount(BigDecimal.valueOf(amount));
        e.setApprovalStatus(status);
        return e;
    }

    private void existing(ExpenseEntity... rows) {
        when(repo.findByTenantIdAndExpenseDateBetweenAndConsultantEmailIgnoreCase(any(), any(), any(), eq(EMAIL)))
                .thenReturn(List.of(rows));
    }

    @Test
    void montantSuperieurAuPlafond_estRamenéAuPlafondAvecVerdict() throws Exception {
        when(policy.findKmDailyCap()).thenReturn(Optional.of(CAP_29));
        existing();

        List<ExpenseItem> result = applyCap(List.of(kmLine("2026-10-01", 42.01)));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAmount()).isEqualTo(29.0);
        assertThat(result.get(0).getPolicyVerdictJson()).isEqualTo("{\"compliant\":false}");
        verify(policy).kmCapVerdictJson(CAP_29, 42.01, 0.0, 29.0);
    }

    @Test
    void cumulAvecLigneExistante_neDonneQueLeResteDuJour() throws Exception {
        when(policy.findKmDailyCap()).thenReturn(Optional.of(CAP_29));
        existing(existingKm("2026-10-01", 21.27, "PENDING"),
                 existingKm("2026-10-01", 50.00, "REFUSED")); // les refusées ne comptent pas

        List<ExpenseItem> result = applyCap(List.of(kmLine("2026-10-01", 42.01)));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAmount()).isEqualTo(7.73);
    }

    @Test
    void plafondDéjàAtteint_ligneNonCréée() throws Exception {
        when(policy.findKmDailyCap()).thenReturn(Optional.of(CAP_29));
        existing(existingKm("2026-10-01", 29.00, "APPROVED"));

        List<ExpenseItem> result = applyCap(List.of(kmLine("2026-10-01", 21.27), kmLine("2026-10-02", 21.27)));

        assertThat(result).extracting(ExpenseItem::getDate).containsExactly("2026-10-02");
        assertThat(result.get(0).getAmount()).isEqualTo(21.27);
        assertThat(result.get(0).getPolicyVerdictJson()).isNull();
    }

    @Test
    void sansPlafondDansLaPolitique_montantsInchangés() throws Exception {
        when(policy.findKmDailyCap()).thenReturn(Optional.empty());

        List<ExpenseItem> result = applyCap(List.of(kmLine("2026-10-01", 42.01)));

        assertThat(result.get(0).getAmount()).isEqualTo(42.01);
        verify(repo, never()).findByTenantIdAndExpenseDateBetweenAndConsultantEmailIgnoreCase(any(), any(), any(), any());
    }

    @Test
    void autreTypeQueFraisKm_nonConcerné() throws Exception {
        ExpenseItem resto = kmLine("2026-10-01", 120.0);
        resto.setType("restaurant");

        List<ExpenseItem> result = applyCap(List.of(resto));

        assertThat(result.get(0).getAmount()).isEqualTo(120.0);
        verify(policy, never()).findKmDailyCap();
    }
}
