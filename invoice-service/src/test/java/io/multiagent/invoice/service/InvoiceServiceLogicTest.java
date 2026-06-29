package io.multiagent.invoice.service;

import io.multiagent.invoice.model.AbsencePeriod;
import io.multiagent.invoice.model.SimpleInvoiceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests des règles métier pures d'InvoiceService via réflexion.
 * Couvre : calcul jours ouvrés, dates facture, numéro facture.
 */
@DisplayName("InvoiceService — règles métier")
class InvoiceServiceLogicTest {

    private InvoiceService service;

    @BeforeEach
    void setUp() {
        service = mock(InvoiceService.class, CALLS_REAL_METHODS);
    }

    // ── resolveDaysCount ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveDaysCount — jours ouvrés")
    class DaysCount {

        @Test
        @DisplayName("Juin 2026 = 22 jours ouvrés (absence weekend ne réduit pas)")
        void juin2026SansAbsencesReelles() throws Exception {
            // resolveDaysCount ne calcule que si absencePeriods non vide
            // → on passe une absence sur un weekend pour déclencher le calcul
            AbsencePeriod weekendAbsence = new AbsencePeriod();
            weekendAbsence.setFrom("2026-06-07"); // dimanche
            weekendAbsence.setTo("2026-06-07");
            SimpleInvoiceRequest req = req(null, "2026-06", List.of(weekendAbsence), null);
            double days = invokeDays(req, "2026-06");
            assertThat(days).isEqualTo(22.0); // juin 2026 = 22 jours ouvrés
        }

        @Test
        @DisplayName("daysCount explicite retourné tel quel quand absences null")
        void explicitDaysCountNoAbsences() throws Exception {
            SimpleInvoiceRequest req = req(19.0, "2026-06", null, null);
            Double days = invokeDays(req, "2026-06");
            assertThat(days).isEqualTo(19.0);
        }

        @Test
        @DisplayName("Absence d'une semaine (lun-ven) → 22 - 5 = 17")
        void avecAbsenceUneSemaine() throws Exception {
            AbsencePeriod absence = new AbsencePeriod();
            absence.setFrom("2026-06-01"); // lundi
            absence.setTo("2026-06-05");   // vendredi
            SimpleInvoiceRequest req = req(null, "2026-06", List.of(absence), null);
            double days = invokeDays(req, "2026-06");
            assertThat(days).isEqualTo(17.0); // 22 - 5 = 17
        }

        @Test
        @DisplayName("Mai 2026 : 1er mai + 8 mai fériés → 19 jours ouvrés")
        void mai2026AvecFeries() throws Exception {
            // Passer une absence weekend pour déclencher le calcul
            AbsencePeriod weekendAbsence = new AbsencePeriod();
            weekendAbsence.setFrom("2026-05-03"); // dimanche
            weekendAbsence.setTo("2026-05-03");
            SimpleInvoiceRequest req = req(null, "2026-05", List.of(weekendAbsence), null);
            double days = invokeDays(req, "2026-05");
            assertThat(days).isEqualTo(19.0); // 21 bruts - 1er mai - 8 mai = 19
        }

        private double invokeDays(SimpleInvoiceRequest req, String billingMonth) throws Exception {
            Method m = InvoiceService.class.getDeclaredMethod(
                    "resolveDaysCount", SimpleInvoiceRequest.class, String.class);
            m.setAccessible(true);
            return (double) m.invoke(service, req, billingMonth);
        }
    }

    // ── resolveInvoiceDate ────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveInvoiceDate — date de facture")
    class InvoiceDate {

        @Test
        @DisplayName("CRA juin validé → date facture = 2026-07-01 (1er du mois suivant)")
        void cra_juin_valide_invoice_date_juillet() throws Exception {
            SimpleInvoiceRequest req = req(null, "2026-06", null, null);
            LocalDate date = invokeDate(req, "2026-06");
            assertThat(date).isEqualTo(LocalDate.of(2026, 7, 1));
        }

        @Test
        @DisplayName("invoiceDate explicite → conservée")
        void explicit_invoice_date_preserved() throws Exception {
            SimpleInvoiceRequest req = new SimpleInvoiceRequest(
                    null, LocalDate.of(2026, 7, 15), "2026-06",
                    null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null);
            LocalDate date = invokeDate(req, "2026-06");
            assertThat(date).isEqualTo(LocalDate.of(2026, 7, 15));
        }

        private LocalDate invokeDate(SimpleInvoiceRequest req, String billingMonth) throws Exception {
            Method m = InvoiceService.class.getDeclaredMethod(
                    "resolveInvoiceDate", SimpleInvoiceRequest.class, String.class);
            m.setAccessible(true);
            return (LocalDate) m.invoke(service, req, billingMonth);
        }
    }

    // ── resolveBillingMonth ───────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveBillingMonth")
    class BillingMonth {

        @Test
        @DisplayName("billingMonth fourni → retourné tel quel")
        void explicit_billing_month() throws Exception {
            SimpleInvoiceRequest req = req(null, "2026-06", null, null);
            String month = invokeMonth(req, null);
            assertThat(month).isEqualTo("2026-06");
        }

        @Test
        @DisplayName("billingMonth null + invoiceDate → mois de l'invoiceDate")
        void from_invoice_date() throws Exception {
            SimpleInvoiceRequest req = new SimpleInvoiceRequest(
                    null, LocalDate.of(2026, 7, 1), null,
                    null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null);
            String month = invokeMonth(req, LocalDate.of(2026, 7, 1));
            assertThat(month).isEqualTo("2026-07");
        }

        private String invokeMonth(SimpleInvoiceRequest req, LocalDate invoiceDate) throws Exception {
            Method m = InvoiceService.class.getDeclaredMethod(
                    "resolveBillingMonth", SimpleInvoiceRequest.class, LocalDate.class);
            m.setAccessible(true);
            return (String) m.invoke(service, req, invoiceDate);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private SimpleInvoiceRequest req(Double daysCount, String billingMonth,
                                      List<AbsencePeriod> absences, LocalDate invoiceDate) {
        // invoiceName, invoiceDate, billingMonth, sellerCompanyName, sellerAddress, sellerRcs,
        // clientCompanyName, clientAddress, clientRcs, invoiceTitle,
        // daysCount, unitPriceHt, totalHt, vatRate, totalTtc, currency,
        // paymentDueDate, latePaymentClause, notes, absencePeriods, consultantEmail, projectName
        return new SimpleInvoiceRequest(
                null, invoiceDate, billingMonth,
                null, null, null, null, null, null, null,
                daysCount, null, null, null, null, null,
                null, null, null, absences, null, null);
    }
}
