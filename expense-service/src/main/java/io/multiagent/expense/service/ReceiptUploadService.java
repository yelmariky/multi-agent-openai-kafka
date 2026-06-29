package io.multiagent.expense.service;

import io.multiagent.expense.infrastructure.tenant.TenantContext;
import io.multiagent.expense.model.ReceiptDuplicateInfo;
import io.multiagent.expense.model.ReceiptUploadResponse;
import io.multiagent.expense.model.ExpenseItem;
import io.multiagent.expense.util.DateProvider;
import io.multiagent.expense.service.ExpenseDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReceiptUploadService {

    private final ReceiptStorageService storage;
    private final OcrService ocr;
    private final OcrExtractionService extraction;
    private final DuplicateDetectorService duplicateDetector;
    private final ExpenseDataService expenseDataService;
    private final DateProvider dateProvider;
    private final ExpenseIdGenerator idGenerator;

    @Async
    public CompletableFuture<ReceiptUploadResponse> handleUpload(
            MultipartFile file, String paymentMode, String consultantEmail,
            UUID tenantId, String realm) {
        // Propager le contexte tenant dans le thread async (ThreadLocal non hérité)
        if (tenantId != null) TenantContext.set(tenantId, realm);

        try {
            if (file == null || file.isEmpty()) {
                throw new IllegalArgumentException("Fichier manquant ou vide");
            }

            String id = UUID.randomUUID().toString();
            byte[] bytes = file.getBytes();
            String binaryHash = sha256(bytes);

            Path saved = storage.save(file, id);
            String ocrText = ocr.extractText(saved);
            String normalized = normalizeText(ocrText);
            String textHash = sha256(normalized.getBytes(StandardCharsets.UTF_8));

            ReceiptDuplicateInfo dup = duplicateDetector.track(id, binaryHash, textHash);
            // Passer le chemin image pour le fallback Vision si OCR trop bruité
            String extracted = extraction.extractExpenseJson(ocrText, saved);

            ReceiptUploadResponse response = ReceiptUploadResponse.builder()
                    .id(id)
                    .originalFilename(file.getOriginalFilename())
                    .binaryHash(binaryHash)
                    .textHash(textHash)
                    .binaryDuplicate(dup.isBinaryDuplicate())
                    .textDuplicate(dup.isTextDuplicate())
                    .duplicateOfBinary(dup.binaryOf())
                    .duplicateOfText(dup.textOf())
                    .ocrText(ocrText)
                    .extractedExpenseJson(extracted)
                    .build();

            // Indexer la dépense structurée si possible
            ExpenseItem expense = parseExpense(extracted);
            if (expense != null) {
                try {
                    // resolveDate() gère LocalDate, LocalDateTime et OffsetDateTime
                    LocalDate d = resolveDate(expense.getDate());
                    expense.setId(idGenerator.nextId(d));
                } catch (Exception e) {
                    log.warn("⚠️ Impossible de générer l’id incrémental (upload) : {}", e.getMessage());
                }
                // Email consultant — propagé pour que approve/refuse SSE fonctionne
                if (!isBlank(consultantEmail) && isBlank(expense.getConsultantEmail())) {
                    expense.setConsultantEmail(consultantEmail.toLowerCase(Locale.ROOT).trim());
                }
                // Fallbacks légers
                if (isBlank(expense.getPaymentMode())) {
                    expense.setPaymentMode(isBlank(paymentMode) ? "Personnel" : paymentMode);
                }
                if (isBlank(expense.getAddress())) {
                    expense.setAddress("inconnue");
                }
                expenseDataService.indexExpense(id, expense, "receipt", dup.isBinaryDuplicate() || dup.isTextDuplicate(), binaryHash);
            }

            return CompletableFuture.completedFuture(response);
        } catch (Exception e) {
            throw new IllegalStateException("Upload échoué: " + e.getMessage(), e);
        } finally {
            TenantContext.clear();
        }
    }

    private String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Impossible de calculer SHA-256", e);
        }
    }

    private String normalizeText(String text) {
        if (text == null) return "";
        String noAccents = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        String lower = noAccents.toLowerCase(Locale.ROOT);
        String compact = lower.replaceAll("[^a-z0-9€., ]", " ");
        return compact.replaceAll("\\s+", " ").trim();
    }

    private ExpenseItem parseExpense(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            // Le LLM renvoie un objet JSON ; on l'entoure en tableau pour réutiliser fromJsonArray
            String arr = "[" + json + "]";
            return ExpenseItem.fromJsonArray(arr).stream().findFirst().orElse(null);
        } catch (Exception e) {
            log.warn("⚠️ Impossible de parser la dépense extraite: {}", e.getMessage());
            return null;
        }
    }

    private LocalDate resolveDate(String date) {
        if (date == null || date.isBlank()) {
            return dateProvider.todayUtc();
        }
        // YYYY-MM-DD
        try { return LocalDate.parse(date); } catch (Exception ignored) {}
        // YYYY-MM-DDTHH:mm:ss (sans offset — cas OCR ticket de caisse)
        try { return LocalDateTime.parse(date, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toLocalDate(); } catch (Exception ignored) {}
        // YYYY-MM-DDTHH:mm:ssZ / +HH:mm
        try { return OffsetDateTime.parse(date).toLocalDate(); } catch (Exception ignored) {}
        return dateProvider.todayUtc();
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

}
