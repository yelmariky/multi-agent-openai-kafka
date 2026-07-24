package io.multiagent.invoice.controller;

import io.multiagent.invoice.entity.InvoiceEntity;
import io.multiagent.invoice.infrastructure.tenant.TenantContext;
import io.multiagent.invoice.repository.InvoiceJpaRepository;
import io.multiagent.invoice.service.InvoiceDunningService;
import io.multiagent.invoice.service.InvoicePaymentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;

/**
 * Console plateforme — suivi des factures d'abonnement SaaS émises par IA-INSIGHT
 * à ses organisations clientes (statut de paiement, relances).
 *
 * Le platform_admin (realm "platform") n'a pas de TenantContext : ces endpoints
 * résolvent le tenant vendeur par configuration (billing.seller-slug) et posent
 * le contexte explicitement — même pattern que les schedulers.
 */
@Slf4j
@RestController
@RequestMapping("/invoices/platform")
public class PlatformInvoiceController {

    private final InvoiceJpaRepository invoiceRepo;
    private final InvoicePaymentService invoicePaymentService;
    private final InvoiceDunningService invoiceDunningService;
    private final JdbcTemplate jdbcTemplate;
    private final String sellerSlug;

    public PlatformInvoiceController(InvoiceJpaRepository invoiceRepo,
                                     InvoicePaymentService invoicePaymentService,
                                     InvoiceDunningService invoiceDunningService,
                                     JdbcTemplate jdbcTemplate,
                                     @Value("${billing.seller-slug:ia-insight}") String sellerSlug) {
        this.invoiceRepo = invoiceRepo;
        this.invoicePaymentService = invoicePaymentService;
        this.invoiceDunningService = invoiceDunningService;
        this.jdbcTemplate = jdbcTemplate;
        this.sellerSlug = sellerSlug;
    }

    /** Factures d'abonnement (ABO-*) du tenant vendeur, avec statut et dernière relance. */
    @GetMapping("/subscription-invoices")
    public ResponseEntity<?> subscriptionInvoices() {
        UUID sellerId = resolveSellerOrgId();
        if (sellerId == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Organisation vendeuse introuvable : " + sellerSlug));
        }

        // Dernière relance par facture, en une requête
        Map<Object, Map<String, Object>> lastDunning = new HashMap<>();
        jdbcTemplate.queryForList(
                "SELECT invoice_id, MAX(stage) AS stage, MAX(sent_at) AS sent_at "
                + "FROM invoice_dunning_log WHERE tenant_id = ? GROUP BY invoice_id", sellerId)
                .forEach(r -> lastDunning.put(r.get("invoice_id"),
                        Map.of("stage", r.get("stage"), "sentAt", r.get("sent_at"))));

        LocalDate today = LocalDate.now();
        List<Map<String, Object>> out = invoiceRepo.findByTenantId(sellerId).stream()
                .filter(e -> e.getInvoiceName() != null && e.getInvoiceName().startsWith("ABO"))
                .sorted(Comparator.comparing(InvoiceEntity::getInvoiceDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(e -> {
                    String status = e.getPaymentStatus() != null ? e.getPaymentStatus() : "EN_ATTENTE";
                    if (!"PAYEE".equals(status) && e.getPaymentDueDate() != null
                            && e.getPaymentDueDate().isBefore(today)) {
                        status = "EN_RETARD";
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", e.getId());
                    m.put("invoiceName", e.getInvoiceName());
                    m.put("clientCompanyName", e.getClientCompanyName());
                    m.put("invoiceDate", e.getInvoiceDate());
                    m.put("paymentDueDate", e.getPaymentDueDate());
                    m.put("totalTtc", e.getTotalTtc());
                    m.put("currency", e.getCurrency());
                    m.put("paymentStatus", status);
                    Map<String, Object> d = lastDunning.get(e.getId());
                    m.put("lastDunningStage", d != null ? d.get("stage") : null);
                    m.put("lastDunningDate", d != null ? d.get("sentAt") : null);
                    return m;
                })
                .toList();
        return ResponseEntity.ok(out);
    }

    @PutMapping("/{id}/mark-sent")
    public ResponseEntity<?> markSent(@PathVariable UUID id) {
        return asSeller(() -> ResponseEntity.ok(invoicePaymentService.markSent(id)));
    }

    @PutMapping("/{id}/mark-paid")
    public ResponseEntity<?> markPaid(@PathVariable UUID id,
                                      @RequestBody(required = false) Map<String, String> body) {
        String date = body != null ? body.get("paymentDate") : null;
        return asSeller(() -> ResponseEntity.ok(invoicePaymentService.markPaid(id, date)));
    }

    @PostMapping("/{id}/dunning")
    public ResponseEntity<?> dun(@PathVariable UUID id) {
        UUID sellerId = resolveSellerOrgId();
        if (sellerId == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Organisation vendeuse introuvable"));
        }
        try {
            return ResponseEntity.ok(invoiceDunningService.dunManually(sellerId, id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Exécute une action avec le TenantContext du vendeur (le platform_admin n'en a pas). */
    private ResponseEntity<?> asSeller(Supplier<ResponseEntity<?>> action) {
        UUID sellerId = resolveSellerOrgId();
        if (sellerId == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Organisation vendeuse introuvable"));
        }
        try {
            TenantContext.set(sellerId, sellerSlug);
            return action.get();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } finally {
            TenantContext.clear();
        }
    }

    private UUID resolveSellerOrgId() {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id FROM organization WHERE slug = ? LIMIT 1", UUID.class, sellerSlug);
        } catch (Exception e) {
            log.error("[PlatformInvoices] Organisation vendeuse '{}' introuvable : {}", sellerSlug, e.getMessage());
            return null;
        }
    }
}
