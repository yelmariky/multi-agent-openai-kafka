package io.multiagent.core.expense.controller;

import io.multiagent.core.infrastructure.tenant.TenantContext;
import io.multiagent.core.model.ReceiptUploadResponse;
import io.multiagent.core.expense.service.ReceiptUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
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
}
