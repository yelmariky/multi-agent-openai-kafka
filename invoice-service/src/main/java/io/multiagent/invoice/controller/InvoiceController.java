package io.multiagent.invoice.controller;

import io.multiagent.invoice.model.InvoiceLookupRequest;
import io.multiagent.invoice.model.SimpleInvoiceRequest;
import io.multiagent.invoice.model.SubscriptionInvoiceRequest;
import io.multiagent.invoice.service.InvoiceService;
import io.multiagent.invoice.service.InvoicePaymentService;
import io.multiagent.invoice.infrastructure.tenant.TenantContext;
import io.multiagent.invoice.service.InvoiceCancellationService;
import io.multiagent.invoice.service.InvoiceDunningService;
import io.multiagent.invoice.service.SubscriptionBillingScheduler;
import io.multiagent.invoice.service.SubscriptionInvoiceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/invoices")
public class InvoiceController {

    private static final String ATTACHMENT_PREFIX = "attachment; filename=\"";

    private final InvoiceService             invoiceService;
    private final InvoicePaymentService      invoicePaymentService;
    private final SubscriptionInvoiceService subscriptionInvoiceService;
    private final SubscriptionBillingScheduler subscriptionBillingScheduler;
    private final InvoiceDunningService      invoiceDunningService;
    private final InvoiceCancellationService invoiceCancellationService;

    public InvoiceController(
            InvoiceService invoiceService,
            InvoicePaymentService invoicePaymentService,
            SubscriptionInvoiceService subscriptionInvoiceService,
            SubscriptionBillingScheduler subscriptionBillingScheduler,
            InvoiceDunningService invoiceDunningService,
            InvoiceCancellationService invoiceCancellationService) {
        this.invoiceService               = invoiceService;
        this.invoicePaymentService        = invoicePaymentService;
        this.subscriptionInvoiceService   = subscriptionInvoiceService;
        this.subscriptionBillingScheduler = subscriptionBillingScheduler;
        this.invoiceDunningService        = invoiceDunningService;
        this.invoiceCancellationService   = invoiceCancellationService;
    }

    @GetMapping("/report")
    public ResponseEntity<List<Map<String, Object>>> report(
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) String company,
            @RequestParam(required = false) String consultantEmail
    ) {
        // Version enrichie : inclut id, paymentStatus, paymentDueDate, sentDate, etc.
        return ResponseEntity.ok(invoiceService.findInvoicesByPeriodEnriched(start, end, company, consultantEmail));
    }

    @PostMapping("/generate")
    public ResponseEntity<Object> generate(@RequestBody SimpleInvoiceRequest request) {
        try {
            var generated = invoiceService.generate(request);
            return ResponseEntity.ok(buildInvoiceResponse("Invoice generated successfully", generated.invoice(),
                    generated.pdfPath().toString(), generated.excelPath().toString()));
        } catch (InvoiceService.InvoiceLockedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to generate invoice: " + e.getMessage());
        }
    }

    /** Déclenche immédiatement la facturation des abonnements dus (sinon : cron quotidien 06h00).
     *  Réservé au platform_admin — renvoie le résumé par organisation. */
    @PostMapping("/subscription/run-billing")
    public ResponseEntity<Object> runBilling() {
        try {
            return ResponseEntity.ok(subscriptionBillingScheduler.runBilling());
        } catch (Exception e) {
            log.error("runBilling failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Billing run failed: " + e.getMessage());
        }
    }

    /** Déclenche immédiatement les relances de factures impayées (sinon : cron quotidien 08h00). */
    @PostMapping("/run-dunning")
    public ResponseEntity<Object> runDunning() {
        try {
            return ResponseEntity.ok(invoiceDunningService.runAll());
        } catch (Exception e) {
            log.error("runDunning failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Dunning run failed: " + e.getMessage());
        }
    }

    /** Relance manuelle d'une facture précise (« Relancer maintenant »). */
    @PostMapping("/{id}/dunning")
    public ResponseEntity<Object> dunInvoice(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(invoiceDunningService.dunManually(TenantContext.getTenantId(), id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("dunInvoice failed for {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Dunning failed: " + e.getMessage());
        }
    }

    /** Facture d'abonnement SaaS — grille officielle (29€/49€, -20% annuel) ou prix négocié Enterprise.
     *  Renvoie directement le PDF ; la facture est persistée comme les autres (suivi de paiement inclus). */
    @PostMapping(value = "/subscription", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> generateSubscription(@RequestBody SubscriptionInvoiceRequest request) {
        try {
            var generated = subscriptionInvoiceService.generate(request);
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_DISPOSITION, ATTACHMENT_PREFIX + generated.pdfPath().getFileName() + "\"");
            return ResponseEntity.ok().headers(headers).body(generated.pdfBytes());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("generateSubscription failed for request={}: {}", request, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping(value = "/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> generatePdf(@RequestBody InvoiceLookupRequest request) {
        try {
            var generated = invoiceService.generateFromDb(request);
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_DISPOSITION, ATTACHMENT_PREFIX + generated.pdfPath().getFileName() + "\"");
            return ResponseEntity.ok().headers(headers).body(generated.pdfBytes());
        } catch (Exception e) {
            log.error("generatePdf failed for request={}: {}", request, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping(
            value = "/excel",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    )
    public ResponseEntity<byte[]> generateExcel(@RequestBody InvoiceLookupRequest request) {
        try {
            var generated = invoiceService.generateFromDb(request);
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_DISPOSITION, ATTACHMENT_PREFIX + generated.excelPath().getFileName() + "\"");
            return ResponseEntity.ok().headers(headers).body(generated.excelBytes());
        } catch (Exception e) {
            log.error("generateExcel failed for request={}: {}", request, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /** Annule une facture : brouillon → ANNULEE ; envoyée/en retard → ANNULEE + avoir. Jamais de suppression. */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Object> cancel(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(invoiceCancellationService.cancel(id));
        } catch (InvoiceService.InvoiceLockedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("cancel failed for {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /** Marquer une facture comme envoyée au client. */
    @PutMapping("/{id}/mark-sent")
    public ResponseEntity<Object> markSent(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(invoicePaymentService.markSent(id));
        } catch (InvoiceService.InvoiceLockedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    /** Marquer une facture comme payée (encaissement reçu). */
    @PutMapping("/{id}/mark-paid")
    public ResponseEntity<Object> markPaid(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body) {
        try {
            String dateStr = body != null ? body.get("paymentDate") : null;
            return ResponseEntity.ok(invoicePaymentService.markPaid(id, dateStr));
        } catch (InvoiceService.InvoiceLockedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    /** Liste les factures avec leur statut de paiement, optionnellement filtrées. */
    @GetMapping("/payment-status")
    public ResponseEntity<Object> paymentStatus(
            @RequestParam(name = "status", required = false) String status) {
        try {
            return ResponseEntity.ok(invoicePaymentService.list(status));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    private Map<String, Object> buildInvoiceResponse(String message, SimpleInvoiceRequest inv,
                                                      String pdfPath, String excelPath) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message",           message);
        body.put("invoiceName",       inv.invoiceName()       != null ? inv.invoiceName()       : "");
        body.put("billingMonth",      inv.billingMonth()      != null ? inv.billingMonth()      : "");
        body.put("sellerCompanyName", inv.sellerCompanyName() != null ? inv.sellerCompanyName() : "");
        body.put("clientCompanyName", inv.clientCompanyName() != null ? inv.clientCompanyName() : "");
        body.put("daysCount",        inv.daysCount());
        body.put("unitPriceHt",      inv.unitPriceHt());
        body.put("totalHt",          inv.totalHt());
        body.put("totalTtc",         inv.totalTtc());
        body.put("consultantEmail",  inv.consultantEmail() != null ? inv.consultantEmail() : "");
        body.put("pdfPath",          pdfPath);
        body.put("excelPath",        excelPath);
        return body;
    }
}
