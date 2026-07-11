package io.multiagent.invoice.service;

import io.multiagent.invoice.infrastructure.tenant.TenantContext;
import io.multiagent.invoice.model.SubscriptionInvoiceRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Facturation automatique des abonnements SaaS.
 *
 * Chaque jour à 06h00 : repère les abonnements arrivés à échéance
 * (tenant_subscription.next_invoice_date <= aujourd'hui), compte les consultants
 * facturables du tenant À CET INSTANT (is_consultant = TRUE AND active = TRUE),
 * génère la facture d'abonnement sous le tenant vendeur IA-INSIGHT,
 * puis programme l'échéance suivante (+1/+3/+12 mois).
 */
@Slf4j
@Service
public class SubscriptionBillingScheduler {

    private final SubscriptionInvoiceService subscriptionInvoiceService;
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final String sellerSlug;

    public SubscriptionBillingScheduler(SubscriptionInvoiceService subscriptionInvoiceService,
                                        JdbcTemplate jdbc,
                                        @Value("${billing.auto-enabled:true}") boolean enabled,
                                        @Value("${billing.seller-slug:ia-insight}") String sellerSlug) {
        this.subscriptionInvoiceService = subscriptionInvoiceService;
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.sellerSlug = sellerSlug;
    }

    @Scheduled(cron = "${billing.cron:0 0 6 * * *}")
    public void scheduledRun() {
        if (!enabled) {
            log.info("[Billing] Facturation automatique désactivée (billing.auto-enabled=false)");
            return;
        }
        runBilling();
    }

    /** Exécute la facturation des abonnements dus. @return résumé par organisation. */
    public synchronized List<Map<String, Object>> runBilling() {
        UUID sellerOrgId = findSellerOrgId();
        if (sellerOrgId == null) {
            log.error("[Billing] Organisation vendeuse '{}' introuvable — facturation annulée", sellerSlug);
            return List.of(Map.of("error", "Organisation vendeuse introuvable : " + sellerSlug));
        }

        List<Map<String, Object>> due = jdbc.queryForList("""
                SELECT s.organization_id, s.offer, s.billing_period, s.negotiated_monthly_price_ht,
                       s.client_address, s.client_rcs, s.next_invoice_date,
                       o.name AS org_name
                FROM tenant_subscription s
                JOIN organization o ON o.id = s.organization_id
                WHERE s.active = TRUE
                  AND s.next_invoice_date IS NOT NULL
                  AND s.next_invoice_date <= CURRENT_DATE
                  AND s.organization_id <> ?
                ORDER BY s.next_invoice_date
                """, sellerOrgId);

        log.info("[Billing] {} abonnement(s) à facturer", due.size());
        List<Map<String, Object>> results = new ArrayList<>();

        for (Map<String, Object> sub : due) {
            UUID orgId = (UUID) sub.get("organization_id");
            String orgName = (String) sub.get("org_name");
            try {
                int consultants = countBillableConsultants(orgId);
                if (consultants == 0) {
                    log.warn("[Billing] {} : 0 consultant facturable — facture sautée, échéance repoussée d'un mois", orgName);
                    jdbc.update("UPDATE tenant_subscription SET next_invoice_date = next_invoice_date + INTERVAL '1 month', "
                            + "updated_at = now() WHERE organization_id = ?", orgId);
                    results.add(Map.of("organization", orgName, "status", "SKIPPED_NO_CONSULTANT"));
                    continue;
                }

                String period = (String) sub.get("billing_period");
                LocalDate periodStart = ((java.sql.Date) sub.get("next_invoice_date")).toLocalDate();

                SubscriptionInvoiceRequest request = new SubscriptionInvoiceRequest(
                        orgName,
                        (String) sub.get("client_address"),
                        (String) sub.get("client_rcs"),
                        (String) sub.get("offer"),
                        consultants,
                        period,
                        periodStart,
                        (BigDecimal) sub.get("negotiated_monthly_price_ht"),
                        null,               // vendeur par défaut (IA-INSIGHT, profil résolu en base)
                        LocalDate.now(),
                        "Facture generee automatiquement - " + consultants + " consultant(s) actif(s) au "
                                + LocalDate.now());

                // Les factures d'abonnement appartiennent au tenant vendeur (IA-INSIGHT)
                TenantContext.set(sellerOrgId, sellerSlug);
                var generated = subscriptionInvoiceService.generate(request);

                int months = switch (period) { case "MENSUEL" -> 1; case "TRIMESTRIEL" -> 3; default -> 12; };
                LocalDate periodEnd = periodStart.plusMonths(months).minusDays(1);
                jdbc.update("UPDATE tenant_subscription SET next_invoice_date = ?, "
                        + "billed_consultant_count = ?, current_period_start = ?, current_period_end = ?, "
                        + "updated_at = now() WHERE organization_id = ?",
                        periodStart.plusMonths(months), consultants, periodStart, periodEnd, orgId);

                log.info("[Billing] {} : facture {} générée ({} consultants, {})",
                        orgName, generated.invoice().invoiceName(), consultants, period);
                results.add(Map.of(
                        "organization", orgName,
                        "status", "INVOICED",
                        "invoiceName", generated.invoice().invoiceName(),
                        "consultants", consultants,
                        "totalTtc", generated.invoice().totalTtc()));
            } catch (java.io.IOException | RuntimeException e) {
                log.error("[Billing] Échec facturation {} : {}", orgName, e.getMessage(), e);
                results.add(Map.of("organization", orgName, "status", "ERROR", "error", String.valueOf(e.getMessage())));
            } finally {
                TenantContext.clear();
            }
        }

        results.addAll(reconcileSeatAdditions(sellerOrgId));
        return results;
    }

    /**
     * Ajustement prorata : consultants ajoutés depuis la dernière facture de période.
     * Périodes TRIMESTRIEL/ANNUEL uniquement — en MENSUEL l'ajout est compté à la
     * facture suivante (< 1 mois). Retrait de consultant : pas de remboursement en
     * cours de période, la baisse s'applique au renouvellement.
     */
    private List<Map<String, Object>> reconcileSeatAdditions(UUID sellerOrgId) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> subs = jdbc.queryForList("""
                SELECT s.organization_id, s.offer, s.billing_period, s.negotiated_monthly_price_ht,
                       s.client_address, s.client_rcs, s.billed_consultant_count, s.current_period_end,
                       o.name AS org_name
                FROM tenant_subscription s
                JOIN organization o ON o.id = s.organization_id
                WHERE s.active = TRUE
                  AND s.billing_period IN ('TRIMESTRIEL', 'ANNUEL')
                  AND s.billed_consultant_count IS NOT NULL
                  AND s.current_period_end >= CURRENT_DATE
                  AND s.organization_id <> ?
                """, sellerOrgId);

        for (Map<String, Object> sub : subs) {
            UUID orgId = (UUID) sub.get("organization_id");
            String orgName = (String) sub.get("org_name");
            try {
                int billed = ((Number) sub.get("billed_consultant_count")).intValue();
                int current = countBillableConsultants(orgId);
                if (current <= billed) continue;

                int added = current - billed;
                LocalDate periodEnd = ((java.sql.Date) sub.get("current_period_end")).toLocalDate();
                // Mois de service restants, mois entamé dû (ajout le jour J de la période : 12 mois, pas 13)
                LocalDate today = LocalDate.now();
                LocalDate endExclusive = periodEnd.plusDays(1);
                long fullMonths = java.time.temporal.ChronoUnit.MONTHS.between(today, endExclusive);
                int remainingMonths = (int) fullMonths
                        + (today.plusMonths(fullMonths).isBefore(endExclusive) ? 1 : 0);
                if (remainingMonths < 1) continue;

                TenantContext.set(sellerOrgId, sellerSlug);
                var generated = subscriptionInvoiceService.generateSeatAdjustment(
                        orgName,
                        (String) sub.get("client_address"),
                        (String) sub.get("client_rcs"),
                        (String) sub.get("offer"),
                        added,
                        (String) sub.get("billing_period"),
                        (BigDecimal) sub.get("negotiated_monthly_price_ht"),
                        periodEnd, remainingMonths);

                jdbc.update("UPDATE tenant_subscription SET billed_consultant_count = ?, updated_at = now() "
                        + "WHERE organization_id = ?", current, orgId);

                log.info("[Billing] {} : ajustement {} — +{} consultant(s), {} mois restants",
                        orgName, generated.invoice().invoiceName(), added, remainingMonths);
                results.add(Map.of(
                        "organization", orgName,
                        "status", "ADJUSTED",
                        "invoiceName", generated.invoice().invoiceName(),
                        "addedConsultants", added,
                        "remainingMonths", remainingMonths,
                        "totalTtc", generated.invoice().totalTtc()));
            } catch (java.io.IOException | RuntimeException e) {
                log.error("[Billing] Échec ajustement {} : {}", orgName, e.getMessage(), e);
                results.add(Map.of("organization", orgName, "status", "ADJUST_ERROR", "error", String.valueOf(e.getMessage())));
            } finally {
                TenantContext.clear();
            }
        }
        return results;
    }

    private int countBillableConsultants(UUID tenantId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM consultant_profile WHERE tenant_id = ? AND is_consultant = TRUE AND active = TRUE",
                Integer.class, tenantId);
        return n != null ? n : 0;
    }

    private UUID findSellerOrgId() {
        try {
            return jdbc.queryForObject("SELECT id FROM organization WHERE slug = ? LIMIT 1", UUID.class, sellerSlug);
        } catch (Exception e) {
            return null;
        }
    }
}
