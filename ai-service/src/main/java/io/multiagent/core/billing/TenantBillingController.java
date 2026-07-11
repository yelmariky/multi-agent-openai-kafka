package io.multiagent.core.billing;

import io.multiagent.core.infrastructure.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aperçu tarifaire pour le tenant courant — affiché dans la console admin
 * au moment d'ajouter un consultant (transparence : « +1 consultant = X€/mois »).
 *
 * Le prix reste une propriété de l'abonnement (tenant_subscription),
 * jamais du consultant : le comptage est dynamique.
 */
@RestController
@RequestMapping("/billing")
@RequiredArgsConstructor
public class TenantBillingController {

    private static final Map<String, BigDecimal> MONTHLY_PRICE_HT = Map.of(
            "ESSENTIEL", new BigDecimal("29"),
            "CROISSANCE", new BigDecimal("49")
    );
    private static final BigDecimal ANNUAL_DISCOUNT = new BigDecimal("0.80");

    private final JdbcTemplate jdbc;

    @GetMapping("/preview")
    public ResponseEntity<Map<String, Object>> preview() {
        UUID tenantId = TenantContext.getTenantId();
        Map<String, Object> body = new LinkedHashMap<>();

        List<Map<String, Object>> subs = jdbc.queryForList("""
                SELECT offer, billing_period, negotiated_monthly_price_ht, next_invoice_date, active
                FROM tenant_subscription WHERE organization_id = ?
                """, tenantId);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM consultant_profile WHERE tenant_id = ? AND is_consultant = TRUE AND active = TRUE",
                Integer.class, tenantId);
        int consultants = count != null ? count : 0;
        body.put("billableConsultants", consultants);

        if (subs.isEmpty() || !Boolean.TRUE.equals(subs.get(0).get("active"))) {
            body.put("subscribed", false);
            return ResponseEntity.ok(body);
        }

        Map<String, Object> sub = subs.get(0);
        String offer = (String) sub.get("offer");
        String period = (String) sub.get("billing_period");
        BigDecimal monthly = "ENTERPRISE".equals(offer)
                ? (BigDecimal) sub.get("negotiated_monthly_price_ht")
                : MONTHLY_PRICE_HT.get(offer);
        if (monthly == null) {
            body.put("subscribed", false);
            return ResponseEntity.ok(body);
        }
        BigDecimal effective = "ANNUEL".equals(period) ? monthly.multiply(ANNUAL_DISCOUNT) : monthly;

        body.put("subscribed", true);
        body.put("offer", offer);
        body.put("billingPeriod", period);
        body.put("monthlyPricePerConsultantHt", effective.setScale(2, RoundingMode.HALF_UP));
        body.put("currentMonthlyHt", effective.multiply(BigDecimal.valueOf(consultants)).setScale(2, RoundingMode.HALF_UP));
        body.put("projectedMonthlyHt", effective.multiply(BigDecimal.valueOf(consultants + 1L)).setScale(2, RoundingMode.HALF_UP));
        body.put("nextInvoiceDate", sub.get("next_invoice_date"));
        return ResponseEntity.ok(body);
    }
}
