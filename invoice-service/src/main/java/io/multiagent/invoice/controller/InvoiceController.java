package io.multiagent.invoice.controller;

import io.multiagent.invoice.model.InvoiceLookupRequest;
import io.multiagent.invoice.model.SimpleInvoiceRequest;
import io.multiagent.invoice.service.DeleteInvoiceService;
import io.multiagent.invoice.service.InvoiceService;
import io.multiagent.invoice.service.InvoicePaymentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
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

    private final InvoiceService        invoiceService;
    private final DeleteInvoiceService  deleteInvoiceService;
    private final InvoicePaymentService invoicePaymentService;

    public InvoiceController(
            InvoiceService invoiceService,
            DeleteInvoiceService deleteInvoiceService,
            InvoicePaymentService invoicePaymentService) {
        this.invoiceService        = invoiceService;
        this.deleteInvoiceService  = deleteInvoiceService;
        this.invoicePaymentService = invoicePaymentService;
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
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to generate invoice: " + e.getMessage());
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

    @PostMapping("/delete")
    public ResponseEntity<Object> deleteInvoice(@RequestBody InvoiceLookupRequest request) {
        try {
            int deleted = invoiceService.deleteFromDb(request);
            return ResponseEntity.ok(Map.of(
                    "deleted", deleted,
                    "message", deleted > 0 ? "Invoice deleted successfully" : "No invoice found matching criteria",
                    "invoiceName", request.invoiceName() == null ? "" : request.invoiceName()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to delete invoice: " + e.getMessage());
        }
    }

    @PostMapping("/delete-by-text")
    public ResponseEntity<Map<String, Object>> deleteByText(@RequestBody Map<String, String> body) {
        String text = body.getOrDefault("text", "");
        Map<String, Object> result = deleteInvoiceService.deleteByText(text);
        if (result.containsKey("error") && result.get("deleted") instanceof Integer d && d <= 0) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }

    /** Marquer une facture comme envoyée au client. */
    @PutMapping("/{id}/mark-sent")
    public ResponseEntity<Object> markSent(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(invoicePaymentService.markSent(id));
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
