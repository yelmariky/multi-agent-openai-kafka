package io.multiagent.invoice.controller;

import io.multiagent.invoice.model.InvoiceLookupRequest;
import io.multiagent.invoice.model.SimpleInvoiceRequest;
import io.multiagent.invoice.repository.InvoiceWeaviateRepository;
import io.multiagent.invoice.service.DeleteInvoiceService;
import io.multiagent.invoice.service.InvoiceService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/invoices")
public class InvoiceController {

    private static final String ATTACHMENT_PREFIX = "attachment; filename=\"";

    private final InvoiceService invoiceService;
    private final InvoiceWeaviateRepository invoiceRepo;
    private final DeleteInvoiceService deleteInvoiceService;

    public InvoiceController(
            InvoiceService invoiceService,
            InvoiceWeaviateRepository invoiceRepo,
            DeleteInvoiceService deleteInvoiceService) {
        this.invoiceService = invoiceService;
        this.invoiceRepo = invoiceRepo;
        this.deleteInvoiceService = deleteInvoiceService;
    }

    @GetMapping("/report")
    public ResponseEntity<List<Map<String, Object>>> report(
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) String company,
            @RequestParam(required = false) String consultantEmail
    ) {
        List<SimpleInvoiceRequest> invoices = invoiceRepo.findInvoicesByPeriod(start, end, company, consultantEmail);
        List<Map<String, Object>> result = invoices.stream()
                .map(inv -> buildInvoiceResponse("", inv, "", ""))
                .toList();
        return ResponseEntity.ok(result);
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
    public ResponseEntity<byte[]> generatePdf(@RequestBody InvoiceLookupRequest request) throws IOException {
        var generated = invoiceService.generateFromWeaviate(request);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION, ATTACHMENT_PREFIX + generated.pdfPath().getFileName() + "\"");
        return ResponseEntity.ok().headers(headers).body(generated.pdfBytes());
    }

    @PostMapping(
            value = "/excel",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    )
    public ResponseEntity<byte[]> generateExcel(@RequestBody InvoiceLookupRequest request) throws IOException {
        var generated = invoiceService.generateFromWeaviate(request);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION, ATTACHMENT_PREFIX + generated.excelPath().getFileName() + "\"");
        return ResponseEntity.ok().headers(headers).body(generated.excelBytes());
    }

    @PostMapping("/delete")
    public ResponseEntity<Object> deleteInvoice(@RequestBody InvoiceLookupRequest request) {
        try {
            int deleted = invoiceService.deleteFromWeaviate(request);
            return ResponseEntity.ok(Map.of(
                    "deleted", deleted,
                    "message", deleted > 0 ? "Invoice deleted successfully" : "No invoice found matching criteria",
                    "invoiceName", request.invoiceName() == null ? "" : request.invoiceName()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to delete invoice: " + e.getMessage());
        }
    }

    /**
     * Nouveau endpoint appelé par ai-core (ReasoningService) pour supprimer une facture
     * depuis du texte libre (intent delete_invoice).
     * Body : {"text": "..."}
     */
    @PostMapping("/delete-by-text")
    public ResponseEntity<Map<String, Object>> deleteByText(@RequestBody Map<String, String> body) {
        String text = body.getOrDefault("text", "");
        Map<String, Object> result = deleteInvoiceService.deleteByText(text);
        if (result.containsKey("error") && result.get("deleted") instanceof Integer d && d <= 0) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
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
