package io.multiagent.core.billing;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Console plateforme — pilotage des abonnements SaaS des organisations clientes.
 * Protégé par /platform/** → rôle platform_admin (SecurityConfig).
 *
 * Le nombre de consultants facturables est compté en temps réel
 * (consultant_profile : is_consultant = TRUE AND active = TRUE) — jamais stocké.
 */
@RestController
@RequestMapping("/platform/billing")
@RequiredArgsConstructor
public class PlatformBillingController {

    private static final Map<String, BigDecimal> MONTHLY_PRICE_HT = Map.of(
            "ESSENTIEL", new BigDecimal("29"),
            "CROISSANCE", new BigDecimal("49")
    );
    private static final BigDecimal ANNUAL_DISCOUNT = new BigDecimal("0.80");

    private final JdbcTemplate jdbc;

    /** Vue d'ensemble : chaque organisation avec son offre, ses consultants comptés, son MRR. */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT o.id, o.name, o.slug, o.active AS org_active,
                       s.offer, s.billing_period, s.negotiated_monthly_price_ht,
                       s.start_date, s.next_invoice_date, s.active AS sub_active,
                       s.billed_consultant_count, s.current_period_end,
                       (SELECT COUNT(*) FROM consultant_profile cp
                         WHERE cp.tenant_id = o.id
                           AND cp.is_consultant = TRUE
                           AND cp.active = TRUE) AS consultant_count
                FROM organization o
                LEFT JOIN tenant_subscription s ON s.organization_id = o.id
                ORDER BY o.name
                """);

        BigDecimal totalMrr = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            BigDecimal mrr = computeMrr(row);
            row.put("mrrHt", mrr);
            if (mrr != null) totalMrr = totalMrr.add(mrr);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totalMrrHt", totalMrr.setScale(2, RoundingMode.HALF_UP));
        body.put("totalArrHt", totalMrr.multiply(BigDecimal.valueOf(12)).setScale(2, RoundingMode.HALF_UP));
        body.put("organizations", rows);
        return ResponseEntity.ok(body);
    }

    public record SubscriptionUpsert(
            UUID organizationId,
            String offer,
            String billingPeriod,
            BigDecimal negotiatedMonthlyPriceHt,
            String clientAddress,
            String clientRcs,
            LocalDate startDate
    ) {}

    /** Affecte (ou met à jour) l'offre d'une organisation. La prochaine facture part à startDate. */
    @PutMapping("/subscription")
    public ResponseEntity<Object> upsert(@RequestBody SubscriptionUpsert req) {
        if (req.organizationId() == null || req.offer() == null || req.startDate() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "organizationId, offer et startDate sont obligatoires"));
        }
        String offer = req.offer().trim().toUpperCase();
        if (!MONTHLY_PRICE_HT.containsKey(offer) && !"ENTERPRISE".equals(offer)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Offre inconnue : " + req.offer()));
        }
        if ("ENTERPRISE".equals(offer)
                && (req.negotiatedMonthlyPriceHt() == null || req.negotiatedMonthlyPriceHt().signum() <= 0)) {
            return ResponseEntity.badRequest().body(Map.of("error", "negotiatedMonthlyPriceHt obligatoire pour ENTERPRISE"));
        }
        String period = req.billingPeriod() == null ? "ANNUEL" : req.billingPeriod().trim().toUpperCase();

        jdbc.update("""
                INSERT INTO tenant_subscription
                    (organization_id, offer, billing_period, negotiated_monthly_price_ht,
                     client_address, client_rcs, start_date, next_invoice_date, active, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, now())
                ON CONFLICT (organization_id) DO UPDATE SET
                    offer = EXCLUDED.offer,
                    billing_period = EXCLUDED.billing_period,
                    negotiated_monthly_price_ht = EXCLUDED.negotiated_monthly_price_ht,
                    client_address = EXCLUDED.client_address,
                    client_rcs = EXCLUDED.client_rcs,
                    start_date = EXCLUDED.start_date,
                    next_invoice_date = EXCLUDED.next_invoice_date,
                    active = TRUE,
                    updated_at = now()
                """,
                req.organizationId(), offer, period, req.negotiatedMonthlyPriceHt(),
                req.clientAddress(), req.clientRcs(), req.startDate(), req.startDate());

        return ResponseEntity.ok(Map.of("message", "Abonnement enregistré", "organizationId", req.organizationId()));
    }

    /** Suspend la facturation automatique d'une organisation (l'accès service n'est pas touché). */
    @PutMapping("/subscription/{organizationId}/suspend")
    public ResponseEntity<Object> suspend(@PathVariable UUID organizationId) {
        int n = jdbc.update("UPDATE tenant_subscription SET active = FALSE, updated_at = now() WHERE organization_id = ?",
                organizationId);
        return n > 0 ? ResponseEntity.ok(Map.of("message", "Facturation suspendue"))
                     : ResponseEntity.notFound().build();
    }

    /** MRR effectif HT : consultants × prix mensuel (×0.8 si engagement annuel). */
    private BigDecimal computeMrr(Map<String, Object> row) {
        String offer = (String) row.get("offer");
        if (offer == null || !Boolean.TRUE.equals(row.get("sub_active"))) return null;
        BigDecimal monthly = "ENTERPRISE".equals(offer)
                ? (BigDecimal) row.get("negotiated_monthly_price_ht")
                : MONTHLY_PRICE_HT.get(offer);
        if (monthly == null) return null;
        long count = ((Number) row.get("consultant_count")).longValue();
        BigDecimal mrr = monthly.multiply(BigDecimal.valueOf(count));
        if ("ANNUEL".equals(row.get("billing_period"))) {
            mrr = mrr.multiply(ANNUAL_DISCOUNT);
        }
        return mrr.setScale(2, RoundingMode.HALF_UP);
    }
}
