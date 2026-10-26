package io.multiagent.core.expense.service;

import io.multiagent.core.config.TextKeywords;
import io.multiagent.core.infrastructure.tenant.TenantContext;
import io.multiagent.core.model.ReceiptDuplicateInfo;
import io.multiagent.core.model.ReceiptUploadResponse;
import io.multiagent.core.model.ExpenseItem;
import io.multiagent.core.notification.service.NotificationService;
import io.multiagent.core.policy.service.PolicyComplianceService;
import io.multiagent.core.settings.repository.ConsultantProfileJpaRepository;
import io.multiagent.core.util.DateProvider;
import io.multiagent.core.service.WeaviateService;
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
    private final WeaviateService weaviateService;
    private final DateProvider dateProvider;
    private final ExpenseIdGenerator idGenerator;
    private final ReceiptFileValidator fileValidator;
    private final PolicyComplianceService policyComplianceService;
    private final NotificationService notificationService;
    private final ConsultantProfileJpaRepository consultantProfileRepo;
    private final TextKeywords keywords;
    private final io.multiagent.core.i18n.Messages messages;

    /**
     * Étape 1 : analyse pure du justificatif (OCR + Vision + extraction LLM), sans persistance.
     * Le fichier reste sur disque (retrouvable via {@code id}, voir {@link ReceiptStorageService#findById})
     * pour que {@link #confirmExpense} puisse l'indexer une fois l'aperçu validé par le consultant.
     */
    @Async
    public CompletableFuture<ReceiptUploadResponse> handleUpload(
            MultipartFile file, String paymentMode, String consultantEmail,
            UUID tenantId, String realm) {
        // Validation AVANT tout traitement — pas de try/catch générique pour que l'erreur
        // remonte telle quelle (400 via l'exception handler), sans être noyée en 500.
        fileValidator.validate(file);

        // Propager le contexte tenant dans le thread async (ThreadLocal non hérité)
        if (tenantId != null) TenantContext.set(tenantId, realm);

        try {
            String id = UUID.randomUUID().toString();
            byte[] bytes = file.getBytes();
            String binaryHash = sha256(bytes);

            Path saved = storage.save(file, id);
            String ocrText = ocr.extractText(saved);
            String normalized = normalizeText(ocrText);
            String textHash = sha256(normalized.getBytes(StandardCharsets.UTF_8));

            ReceiptDuplicateInfo dup = duplicateDetector.track(id, binaryHash, textHash);

            // Image compatible Vision (conversion PDF→PNG si besoin) — distincte du fichier brut,
            // faute de quoi un PDF envoyé tel quel à l'API Vision est rejeté (format non supporté).
            Path visionImage = ocr.prepareImageForVision(saved);
            String extracted;
            try {
                extracted = extraction.extractExpenseJson(ocrText, visionImage);
            } finally {
                if (visionImage != null && !visionImage.equals(saved)) {
                    try { java.nio.file.Files.deleteIfExists(visionImage); } catch (Exception ignored) {}
                }
            }
            // Filet de sécurité : malgré la consigne du prompt, le LLM ajoute parfois quand même
            // une mention "paiement non précisé" dans description/originalText quand le document
            // ne précise pas la méthode de paiement — on la retire déterministiquement.
            extracted = stripUnknownPaymentMention(extracted);

            ReceiptUploadResponse.ReceiptUploadResponseBuilder responseBuilder = ReceiptUploadResponse.builder()
                    .id(id)
                    .originalFilename(file.getOriginalFilename())
                    .binaryHash(binaryHash)
                    .textHash(textHash)
                    .binaryDuplicate(dup.isBinaryDuplicate())
                    .textDuplicate(dup.isTextDuplicate())
                    .duplicateOfBinary(dup.binaryOf())
                    .duplicateOfText(dup.textOf())
                    .ocrText(ocrText)
                    .extractedExpenseJson(extracted);

            // Signaler l'échec explicitement (au lieu d'un 200 "silencieux" avec un aperçu vide).
            ExpenseItem expense = parseExpense(extracted);
            if (expense == null) {
                responseBuilder.success(false)
                        .reason(messages.get("receipt.error.noExpense"));
            } else if (expense.getAmount() == null || expense.getAmount() <= 0) {
                responseBuilder.success(false)
                        .reason(messages.get("receipt.error.noAmount"));
            } else if (isDuplicate(expense, consultantEmail)) {
                // Bloqué dès l'extraction — inutile de laisser éditer un doublon dans l'aperçu.
                responseBuilder.success(false)
                        .reason(messages.get("expense.error.duplicate"));
            } else {
                // Consultatif uniquement — affiché au consultant dans l'aperçu pour qu'il puisse
                // justifier (champ Motif) avant confirmation si l'IA détecte un possible écart.
                responseBuilder.policyVerdictJson(policyComplianceService.evaluate(expense));
            }

            return CompletableFuture.completedFuture(responseBuilder.build());
        } catch (Exception e) {
            throw new IllegalStateException("Upload failed: " + e.getMessage(), e);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Étape 2 : confirmation de l'aperçu (éventuellement édité par le consultant) — persiste la dépense.
     * {@code id} doit correspondre à un upload traité par {@link #handleUpload} (fichier retrouvé sur disque).
     */
    @Async
    public CompletableFuture<ReceiptUploadResponse> confirmExpense(
            String id, ExpenseItem edited, String paymentMode, String consultantEmail,
            UUID tenantId, String realm) {
        if (tenantId != null) TenantContext.set(tenantId, realm);
        try {
            Path saved = storage.findById(id).orElse(null);
            if (saved == null) {
                return CompletableFuture.completedFuture(ReceiptUploadResponse.builder()
                        .id(id).success(false)
                        .reason(messages.get("receipt.error.notFound")).build());
            }
            if (edited == null || edited.getAmount() == null || edited.getAmount() <= 0) {
                return CompletableFuture.completedFuture(ReceiptUploadResponse.builder()
                        .id(id).success(false).reason(messages.get("receipt.error.invalidAmount")).build());
            }

            try {
                LocalDate d = resolveDate(edited.getDate());
                edited.setId(idGenerator.nextId(d));
            } catch (Exception e) {
                log.warn("⚠️ Impossible de générer l’id incrémental (confirm) : {}", e.getMessage());
            }
            if (!isBlank(consultantEmail) && isBlank(edited.getConsultantEmail())) {
                edited.setConsultantEmail(consultantEmail.toLowerCase(Locale.ROOT).trim());
            }
            if (isBlank(edited.getPaymentMode())) {
                edited.setPaymentMode(isBlank(paymentMode) ? "Personnel" : paymentMode);
            }
            if (isBlank(edited.getAddress())) {
                edited.setAddress(messages.getDefault("expense.default.unknownAddress"));
            }

            // Ré-exécuté par sécurité : les champs ont pu être édités dans l'aperçu depuis l'extraction.
            if (isDuplicate(edited, consultantEmail)) {
                return CompletableFuture.completedFuture(ReceiptUploadResponse.builder()
                        .id(id).success(false)
                        .reason(messages.get("expense.error.duplicate"))
                        .build());
            }

            // Recalculé sur les valeurs finales (montant/type ont pu être édités dans l'aperçu) —
            // le verdict de l'aperçu peut être périmé, celui persisté doit correspondre à ce qui
            // est réellement soumis.
            edited.setPolicyVerdictJson(policyComplianceService.evaluate(edited));

            String binaryHash = sha256(java.nio.file.Files.readAllBytes(saved));
            weaviateService.indexExpense(id, edited, "receipt", false, binaryHash, saved.toString());
            notifyAdmin(edited);

            return CompletableFuture.completedFuture(ReceiptUploadResponse.builder()
                    .id(id).success(true).policyVerdictJson(edited.getPolicyVerdictJson()).build());
        } catch (Exception e) {
            throw new IllegalStateException("Confirmation failed: " + e.getMessage(), e);
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
            throw new RuntimeException("Unable to compute SHA-256", e);
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

    // Mention "paiement inconnu" ajoutée par le LLM — expressions AI_CORE_KEYWORDS_UNKNOWN_PAYMENT
    private java.util.regex.Pattern unknownPaymentMention;

    @jakarta.annotation.PostConstruct
    void compileUnknownPaymentMention() {
        unknownPaymentMention = java.util.regex.Pattern.compile(
                "\\s*[,;\\-\\u2013(]*\\s*" + keywords.alternation(TextKeywords.Group.UNKNOWN_PAYMENT) + "\\)?\\.?",
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
    }

    private String stripUnknownPaymentMention(String json) {
        if (json == null || json.isBlank()) return json;
        return unknownPaymentMention.matcher(json).replaceAll("");
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

    /**
     * Notifie l'admin (cloche de la console) de la nouvelle dépense — manquait sur ce pipeline
     * (upload de justificatif, celui réellement utilisé par la majorité des notes de frais) alors
     * que le pipeline texte libre le fait déjà depuis toujours. Best-effort : une erreur ici ne
     * doit jamais faire échouer la création de la dépense, déjà persistée à ce stade.
     */
    private void notifyAdmin(ExpenseItem expense) {
        try {
            String email = isBlank(expense.getConsultantEmail()) ? messages.getDefault("expense.default.unknownConsultant") : expense.getConsultantEmail();
            String name = resolveConsultantName(email);
            String message = messages.getDefault("notification.expense.created", name,
                    expense.getType() != null ? expense.getType() : messages.getDefault("expense.default.genericType"),
                    String.valueOf(expense.getAmount()), String.valueOf(expense.getDate()));
            notificationService.push("EXPENSE_CREATED", name, expense.getConsultantEmail(), message,
                    String.valueOf(expense.getId()));
        } catch (Exception e) {
            log.warn("⚠️ Notification admin ignorée (non bloquant) : {}", e.getMessage());
        }
    }

    /** Résout le nom affichable du consultant — email en repli si profil absent. */
    private String resolveConsultantName(String consultantEmail) {
        if (isBlank(consultantEmail)) return consultantEmail;
        return consultantProfileRepo
                .findByTenantIdAndEmailIgnoreCase(TenantContext.getTenantId(), consultantEmail)
                .map(io.multiagent.core.settings.entity.ConsultantProfileEntity::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(consultantEmail);
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

    /** Vérifie les doublons en résolvant l'email effectif (celui de la requête si absent de l'extraction). */
    private boolean isDuplicate(ExpenseItem expense, String requestConsultantEmail) {
        if (isBlank(expense.getConsultantEmail()) && !isBlank(requestConsultantEmail)) {
            expense.setConsultantEmail(requestConsultantEmail.toLowerCase(Locale.ROOT).trim());
        }
        LocalDate date = resolveDate(expense.getDate());
        return weaviateService.hasDuplicate(expense, date);
    }

}
