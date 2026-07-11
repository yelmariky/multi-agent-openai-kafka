package io.multiagent.invoice.service;

import io.multiagent.invoice.model.SimpleInvoiceRequest;
import io.multiagent.invoice.model.SubscriptionInvoiceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionBillingScheduler — facturation automatique des abonnements")
class SubscriptionBillingSchedulerTest {

    private static final UUID SELLER = UUID.randomUUID();
    private static final UUID CLIENT = UUID.randomUUID();

    @Mock SubscriptionInvoiceService subscriptionInvoiceService;
    @Mock JdbcTemplate jdbc;

    private SubscriptionBillingScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new SubscriptionBillingScheduler(subscriptionInvoiceService, jdbc, true, "ia-insight");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void mockSeller() {
        when(jdbc.queryForObject(contains("FROM organization WHERE slug"), eq(UUID.class), eq("ia-insight")))
                .thenReturn(SELLER);
    }

    private void mockConsultantCount(int count) {
        when(jdbc.queryForObject(contains("FROM consultant_profile"), eq(Integer.class), eq(CLIENT)))
                .thenReturn(count);
    }

    private Map<String, Object> dueSub(String offer, String period, LocalDate nextInvoice) {
        Map<String, Object> row = new HashMap<>();
        row.put("organization_id", CLIENT);
        row.put("offer", offer);
        row.put("billing_period", period);
        row.put("negotiated_monthly_price_ht", null);
        row.put("client_address", "1 rue Test");
        row.put("client_rcs", "RCS 000");
        row.put("next_invoice_date", java.sql.Date.valueOf(nextInvoice));
        row.put("org_name", "ITHECHIA");
        return row;
    }

    private Map<String, Object> reconcileSub(String offer, String period, int billed, LocalDate periodEnd) {
        Map<String, Object> row = new HashMap<>();
        row.put("organization_id", CLIENT);
        row.put("offer", offer);
        row.put("billing_period", period);
        row.put("negotiated_monthly_price_ht", null);
        row.put("client_address", "1 rue Test");
        row.put("client_rcs", "RCS 000");
        row.put("billed_consultant_count", billed);
        row.put("current_period_end", java.sql.Date.valueOf(periodEnd));
        row.put("org_name", "ITHECHIA");
        return row;
    }

    private void mockDue(List<Map<String, Object>> rows) {
        when(jdbc.queryForList(contains("next_invoice_date <= CURRENT_DATE"), eq(SELLER))).thenReturn(rows);
    }

    private void mockReconcile(List<Map<String, Object>> rows) {
        when(jdbc.queryForList(contains("billed_consultant_count IS NOT NULL"), eq(SELLER))).thenReturn(rows);
    }

    private void mockGenerate() throws Exception {
        SimpleInvoiceRequest fake = new SimpleInvoiceRequest("ABO-TEST", LocalDate.now(), "2026-07",
                "IA-INSIGHT", null, null, "ITHECHIA", null, null, "Abonnement", 1.0,
                BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("0.20"), new BigDecimal("12.00"),
                "EUR", LocalDate.now().plusDays(30), null, null, null, null, null);
        lenient().when(subscriptionInvoiceService.generate(any()))
                .thenReturn(new InvoiceService.GeneratedInvoiceFiles(fake, Path.of("f.pdf"), Path.of("f.xlsx"), new byte[0], new byte[0]));
        lenient().when(subscriptionInvoiceService.generateSeatAdjustment(anyString(), any(), any(), anyString(),
                        anyInt(), anyString(), any(), any(), anyInt()))
                .thenReturn(new InvoiceService.GeneratedInvoiceFiles(fake, Path.of("f.pdf"), Path.of("f.xlsx"), new byte[0], new byte[0]));
    }

    // ── Facturation de période ───────────────────────────────────────────────

    @Test
    @DisplayName("abonnement dû → facture générée, effectif mémorisé, échéance +12 mois")
    void billsDueSubscriptionAndAdvancesDate() throws Exception {
        mockSeller();
        LocalDate start = LocalDate.now();
        mockDue(new ArrayList<>(List.of(dueSub("CROISSANCE", "ANNUEL", start))));
        mockReconcile(List.of());
        mockConsultantCount(7);
        mockGenerate();

        List<Map<String, Object>> results = scheduler.runBilling();

        assertThat(results).hasSize(1);
        assertThat(results.get(0)).containsEntry("status", "INVOICED").containsEntry("consultants", 7);

        ArgumentCaptor<SubscriptionInvoiceRequest> req = ArgumentCaptor.forClass(SubscriptionInvoiceRequest.class);
        verify(subscriptionInvoiceService).generate(req.capture());
        assertThat(req.getValue().consultantCount()).isEqualTo(7);
        assertThat(req.getValue().offer()).isEqualTo("CROISSANCE");

        verify(jdbc).update(contains("billed_consultant_count = ?"),
                eq(start.plusMonths(12)), eq(7), eq(start), eq(start.plusMonths(12).minusDays(1)), eq(CLIENT));
    }

    @Test
    @DisplayName("0 consultant facturable → facture sautée, échéance repoussée d'un mois")
    void skipsTenantWithoutConsultants() throws Exception {
        mockSeller();
        mockDue(new ArrayList<>(List.of(dueSub("ESSENTIEL", "MENSUEL", LocalDate.now()))));
        mockReconcile(List.of());
        mockConsultantCount(0);

        List<Map<String, Object>> results = scheduler.runBilling();

        assertThat(results.get(0)).containsEntry("status", "SKIPPED_NO_CONSULTANT");
        verify(subscriptionInvoiceService, never()).generate(any());
        verify(jdbc).update(contains("INTERVAL '1 month'"), eq(CLIENT));
    }

    @Test
    @DisplayName("échec de génération → statut ERROR isolé, la boucle continue")
    void generationFailureIsIsolated() throws Exception {
        mockSeller();
        mockDue(new ArrayList<>(List.of(dueSub("CROISSANCE", "ANNUEL", LocalDate.now()))));
        mockReconcile(List.of());
        mockConsultantCount(3);
        when(subscriptionInvoiceService.generate(any())).thenThrow(new RuntimeException("disque plein"));

        List<Map<String, Object>> results = scheduler.runBilling();
        assertThat(results.get(0)).containsEntry("status", "ERROR");
        assertThat((String) results.get(0).get("error")).contains("disque plein");
    }

    @Test
    @DisplayName("organisation vendeuse introuvable → facturation annulée avec erreur explicite")
    void missingSellerAborts() {
        when(jdbc.queryForObject(anyString(), eq(UUID.class), eq("ia-insight")))
                .thenThrow(new RuntimeException("no rows"));

        List<Map<String, Object>> results = scheduler.runBilling();
        assertThat(results).hasSize(1);
        assertThat((String) results.get(0).get("error")).contains("ia-insight");
        verifyNoInteractions(subscriptionInvoiceService);
    }

    @Test
    @DisplayName("scheduledRun désactivé (billing.auto-enabled=false) → aucune action")
    void disabledSchedulerDoesNothing() {
        SubscriptionBillingScheduler off =
                new SubscriptionBillingScheduler(subscriptionInvoiceService, jdbc, false, "ia-insight");
        off.scheduledRun();
        verifyNoInteractions(jdbc, subscriptionInvoiceService);
    }

    @Test
    @DisplayName("scheduledRun activé → délègue à runBilling")
    void enabledSchedulerRuns() {
        mockSeller();
        mockDue(List.of());
        mockReconcile(List.of());
        scheduler.scheduledRun();
        verify(jdbc, times(2)).queryForList(anyString(), eq(SELLER));
    }

    // ── Ajustement prorata ───────────────────────────────────────────────────

    @Test
    @DisplayName("consultant ajouté en cours de période annuelle → ajustement prorata (mois entamé dû)")
    void reconcileGeneratesProratedAdjustment() throws Exception {
        mockSeller();
        mockDue(List.of());
        // Période facturée pour 1 consultant, il y en a maintenant 3 ; fin de période dans 12 mois jour pour jour
        LocalDate periodEnd = LocalDate.now().plusMonths(12).minusDays(1);
        mockReconcile(new ArrayList<>(List.of(reconcileSub("CROISSANCE", "ANNUEL", 1, periodEnd))));
        mockConsultantCount(3);
        mockGenerate();

        List<Map<String, Object>> results = scheduler.runBilling();

        assertThat(results).hasSize(1);
        assertThat(results.get(0))
                .containsEntry("status", "ADJUSTED")
                .containsEntry("addedConsultants", 2)
                .containsEntry("remainingMonths", 12);
        verify(subscriptionInvoiceService).generateSeatAdjustment(eq("ITHECHIA"), any(), any(),
                eq("CROISSANCE"), eq(2), eq("ANNUEL"), isNull(), eq(periodEnd), eq(12));
        verify(jdbc).update(contains("SET billed_consultant_count = ?"), eq(3), eq(CLIENT));
    }

    @Test
    @DisplayName("effectif stable ou en baisse → aucun ajustement (pas de remboursement en cours de période)")
    void reconcileSkipsWhenNoAddition() throws Exception {
        mockSeller();
        mockDue(List.of());
        mockReconcile(new ArrayList<>(List.of(reconcileSub("CROISSANCE", "ANNUEL", 5, LocalDate.now().plusMonths(6)))));
        mockConsultantCount(4);

        List<Map<String, Object>> results = scheduler.runBilling();
        assertThat(results).isEmpty();
        verify(subscriptionInvoiceService, never()).generateSeatAdjustment(any(), any(), any(), any(),
                anyInt(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("échec d'ajustement → statut ADJUST_ERROR isolé")
    void adjustmentFailureIsIsolated() throws Exception {
        mockSeller();
        mockDue(List.of());
        mockReconcile(new ArrayList<>(List.of(reconcileSub("CROISSANCE", "ANNUEL", 1, LocalDate.now().plusMonths(2)))));
        mockConsultantCount(2);
        when(subscriptionInvoiceService.generateSeatAdjustment(any(), any(), any(), any(),
                anyInt(), any(), any(), any(), anyInt())).thenThrow(new RuntimeException("boom"));

        List<Map<String, Object>> results = scheduler.runBilling();
        assertThat(results.get(0)).containsEntry("status", "ADJUST_ERROR");
    }
}
