package io.multiagent.core.billing;

import io.multiagent.core.infrastructure.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantBillingController — aperçu tarifaire à l'ajout d'un consultant")
class TenantBillingControllerTest {

    private static final UUID TENANT = UUID.randomUUID();

    @Mock JdbcTemplate jdbc;

    @InjectMocks TenantBillingController controller;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT, "ia-insight");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Map<String, Object> sub(String offer, String period, BigDecimal negotiated, boolean active) {
        Map<String, Object> row = new HashMap<>();
        row.put("offer", offer);
        row.put("billing_period", period);
        row.put("negotiated_monthly_price_ht", negotiated);
        row.put("next_invoice_date", java.sql.Date.valueOf("2026-08-01"));
        row.put("active", active);
        return row;
    }

    private void mockCount(int consultants) {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(TENANT))).thenReturn(consultants);
    }

    @Test
    @DisplayName("sans abonnement → subscribed=false mais le comptage reste exposé")
    void noSubscriptionReturnsUnsubscribed() {
        when(jdbc.queryForList(anyString(), eq(TENANT))).thenReturn(List.of());
        mockCount(4);

        Map<String, Object> body = controller.preview().getBody();
        assertThat(body).containsEntry("subscribed", false).containsEntry("billableConsultants", 4);
    }

    @Test
    @DisplayName("abonnement suspendu → subscribed=false")
    void suspendedSubscriptionReturnsUnsubscribed() {
        when(jdbc.queryForList(anyString(), eq(TENANT))).thenReturn(List.of(sub("CROISSANCE", "ANNUEL", null, false)));
        mockCount(4);

        assertThat(controller.preview().getBody()).containsEntry("subscribed", false);
    }

    @Test
    @DisplayName("Essentiel mensuel, 4 consultants → 29€, actuel 116€, projeté 145€")
    void monthlyEssentielComputesProjection() {
        when(jdbc.queryForList(anyString(), eq(TENANT))).thenReturn(List.of(sub("ESSENTIEL", "MENSUEL", null, true)));
        mockCount(4);

        Map<String, Object> body = controller.preview().getBody();
        assertThat(body)
                .containsEntry("subscribed", true)
                .containsEntry("offer", "ESSENTIEL")
                .containsEntry("monthlyPricePerConsultantHt", new BigDecimal("29.00"))
                .containsEntry("currentMonthlyHt", new BigDecimal("116.00"))
                .containsEntry("projectedMonthlyHt", new BigDecimal("145.00"));
    }

    @Test
    @DisplayName("Croissance annuel → prix effectif avec remise -20% (39,20€)")
    void annualDiscountApplied() {
        when(jdbc.queryForList(anyString(), eq(TENANT))).thenReturn(List.of(sub("CROISSANCE", "ANNUEL", null, true)));
        mockCount(1);

        Map<String, Object> body = controller.preview().getBody();
        assertThat(body)
                .containsEntry("monthlyPricePerConsultantHt", new BigDecimal("39.20"))
                .containsEntry("projectedMonthlyHt", new BigDecimal("78.40"));
    }

    @Test
    @DisplayName("Enterprise : prix négocié utilisé ; absent → subscribed=false")
    void enterpriseUsesNegotiatedPriceOrDisables() {
        when(jdbc.queryForList(anyString(), eq(TENANT))).thenReturn(List.of(sub("ENTERPRISE", "MENSUEL", new BigDecimal("99"), true)));
        mockCount(2);
        assertThat(controller.preview().getBody())
                .containsEntry("currentMonthlyHt", new BigDecimal("198.00"));

        when(jdbc.queryForList(anyString(), eq(TENANT))).thenReturn(List.of(sub("ENTERPRISE", "MENSUEL", null, true)));
        assertThat(controller.preview().getBody()).containsEntry("subscribed", false);
    }
}
