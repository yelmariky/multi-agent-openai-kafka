package io.multiagent.notefrais.expense.controller;

import io.multiagent.notefrais.expense.service.ReceiptUploadService;
import io.multiagent.notefrais.model.ReceiptUploadResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
            @RequestPart(value = "paymentMode", required = false) String paymentMode) {
        log.info("Upload reçu: {} (paymentMode={})", file != null ? file.getOriginalFilename() : "null", paymentMode);
        return uploadService.handleUpload(file, paymentMode);
    }
}
