package io.multiagent.core.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformBillingController — abonnements & MRR console plateforme")
class PlatformBillingControllerTest {

    @Mock JdbcTemplate jdbc;

    @InjectMocks PlatformBillingController controller;

    private Map<String, Object> orgRow(String name, String offer, String period,
                                       BigDecimal negotiated, long consultants, Boolean subActive) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("name", name);
        row.put("offer", offer);
        row.put("billing_period", period);
        row.put("negotiated_monthly_price_ht", negotiated);
        row.put("consultant_count", consultants);
        row.put("sub_active", subActive);
        return row;
    }

    @Test
    @DisplayName("overview : MRR par organisation (remise annuelle -20%) et totaux MRR/ARR")
    void overviewComputesMrrAndTotals() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(orgRow("ESN-A", "CROISSANCE", "ANNUEL", null, 3, true));   // 3 x 49 x 0.8 = 117.60
        rows.add(orgRow("ESN-B", "ESSENTIEL", "MENSUEL", null, 2, true));   // 2 x 29        = 58.00
        rows.add(orgRow("SansAbo", null, null, null, 5, null));             // pas d'abonnement
        when(jdbc.queryForList(anyString())).thenReturn(rows);

        Map<String, Object> body = controller.overview().getBody();

        assertThat(body).isNotNull();
        assertThat(body.get("totalMrrHt")).isEqualTo(new BigDecimal("175.60"));
        assertThat(body.get("totalArrHt")).isEqualTo(new BigDecimal("2107.20"));
        assertThat(rows.get(0)).containsEntry("mrrHt", new BigDecimal("117.60"));
        assertThat(rows.get(1)).containsEntry("mrrHt", new BigDecimal("58.00"));
        assertThat(rows.get(2).get("mrrHt")).isNull();
    }

    @Test
    @DisplayName("overview : Enterprise utilise le prix mensuel négocié")
    void overviewUsesNegotiatedPriceForEnterprise() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(orgRow("Groupe-X", "ENTERPRISE", "ANNUEL", new BigDecimal("79"), 10, true)); // 10 x 79 x 0.8 = 632
        when(jdbc.queryForList(anyString())).thenReturn(rows);

        controller.overview();
        assertThat(rows.get(0)).containsEntry("mrrHt", new BigDecimal("632.00"));
    }

    @Test
    @DisplayName("overview : abonnement suspendu → pas de MRR")
    void overviewIgnoresSuspendedSubscriptions() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(orgRow("Suspendu", "CROISSANCE", "ANNUEL", null, 4, false));
        when(jdbc.queryForList(anyString())).thenReturn(rows);

        Map<String, Object> body = controller.overview().getBody();
        assertThat(rows.get(0).get("mrrHt")).isNull();
        assertThat(body.get("totalMrrHt")).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    @DisplayName("upsert : abonnement valide → enregistré avec la date de départ comme prochaine facture")
    void upsertPersistsSubscription() {
        var req = new PlatformBillingController.SubscriptionUpsert(
                UUID.randomUUID(), "croissance", "annuel", null,
                "1 rue Test", "RCS 123", LocalDate.of(2026, 8, 1));

        ResponseEntity<Object> res = controller.upsert(req);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        verify(jdbc).update(anyString(),
                eq(req.organizationId()), eq("CROISSANCE"), eq("ANNUEL"), isNull(),
                eq("1 rue Test"), eq("RCS 123"), eq(req.startDate()), eq(req.startDate()));
    }

    @Test
    @DisplayName("upsert : champs obligatoires manquants ou offre inconnue → 400")
    void upsertRejectsInvalidRequests() {
        assertThat(controller.upsert(new PlatformBillingController.SubscriptionUpsert(
                null, "CROISSANCE", null, null, null, null, LocalDate.now())).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.upsert(new PlatformBillingController.SubscriptionUpsert(
                UUID.randomUUID(), "PLATINE", null, null, null, null, LocalDate.now())).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.upsert(new PlatformBillingController.SubscriptionUpsert(
                UUID.randomUUID(), "CROISSANCE", null, null, null, null, null)).getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("upsert : Enterprise sans prix négocié → 400")
    void upsertRequiresNegotiatedPriceForEnterprise() {
        var req = new PlatformBillingController.SubscriptionUpsert(
                UUID.randomUUID(), "ENTERPRISE", "ANNUEL", null, null, null, LocalDate.now());
        assertThat(controller.upsert(req).getStatusCode().value()).isEqualTo(400);

        var zero = new PlatformBillingController.SubscriptionUpsert(
                UUID.randomUUID(), "ENTERPRISE", "ANNUEL", BigDecimal.ZERO, null, null, LocalDate.now());
        assertThat(controller.upsert(zero).getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("suspend : abonnement existant → 200, inconnu → 404")
    void suspendUpdatesOrReturns404() {
        UUID known = UUID.randomUUID(), unknown = UUID.randomUUID();
        when(jdbc.update(anyString(), eq(known))).thenReturn(1);
        when(jdbc.update(anyString(), eq(unknown))).thenReturn(0);

        assertThat(controller.suspend(known).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.suspend(unknown).getStatusCode().value()).isEqualTo(404);
    }
}
