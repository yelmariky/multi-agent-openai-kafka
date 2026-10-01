package io.multiagent.expense.controller;

import io.multiagent.expense.infrastructure.tenant.TenantContext;
import io.multiagent.expense.model.ExpenseItem;
import io.multiagent.expense.model.ReceiptUploadResponse;
import io.multiagent.expense.service.ReceiptUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/receipts")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin
public class ReceiptController {

    private final ReceiptUploadService uploadService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CompletableFuture<ReceiptUploadResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "paymentMode",    required = false) String paymentMode,
            @RequestPart(value = "consultantEmail", required = false) String consultantEmail) {
        log.info("📩 Upload reçu: {} (paymentMode={}, consultant={})", file != null ? file.getOriginalFilename() : "null", paymentMode, consultantEmail);
        // Capturer le tenant ici (thread HTTP) avant le switch vers le thread @Async
        UUID tenantId = TenantContext.getTenantIdOrNull();
        String realm  = TenantContext.getRealm();
        return uploadService.handleUpload(file, paymentMode, consultantEmail, tenantId, realm);
    }

    public record ConfirmRequest(
            String id, String type, Double amount, String currency, String date,
            String company, String address, String description, String motif,
            String paymentMode, String consultantEmail) {}

    @PostMapping(value = "/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CompletableFuture<ReceiptUploadResponse> confirm(@RequestBody ConfirmRequest req) {
        log.info("✅ Confirmation reçue: id={}, consultant={}", req.id(), req.consultantEmail());
        UUID tenantId = TenantContext.getTenantIdOrNull();
        String realm  = TenantContext.getRealm();

        ExpenseItem edited = new ExpenseItem();
        edited.setType(req.type());
        edited.setAmount(req.amount());
        edited.setCurrency(req.currency());
        edited.setDate(req.date());
        edited.setCompany(req.company());
        edited.setAddress(req.address());
        String description = req.description();
        if (req.motif() != null && !req.motif().isBlank()) {
            description = (description == null || description.isBlank())
                    ? req.motif().trim()
                    : description + " — " + req.motif().trim();
        }
        edited.setDescription(description);

        return uploadService.confirmExpense(req.id(), edited, req.paymentMode(), req.consultantEmail(), tenantId, realm);
    }
}
