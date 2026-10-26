package io.multiagent.core.policy.controller;

import io.multiagent.core.policy.entity.PolicyDocumentEntity;
import io.multiagent.core.policy.service.PolicyFileStorageService;
import io.multiagent.core.policy.service.PolicyIngestionService;
import io.multiagent.core.policy.service.PolicyPdfExtractionService;
import io.multiagent.core.policy.service.PolicyPdfRenderingService;
import io.multiagent.core.security.PromptGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Soumission et activation des politiques tenant (ex: politique de remboursement de notes de
 * frais) — réservé admin, voir SecurityConfig. Une version soumise reste DRAFT tant qu'elle n'est
 * pas explicitement activée : aucune politique ne prend effet silencieusement.
 *
 * <p>Le corps utilise le champ {@code policyText} (et non {@code text}) pour échapper au plafond
 * générique de {@link io.multiagent.core.security.PromptGuardFilter} (4000 caractères, pensé pour
 * un message de chat) — un document de politique légitime peut faire plusieurs pages. La
 * vérification {@link PromptGuard} est appliquée explicitement ici avec un plafond adapté, pas
 * contournée : voir CLAUDE.md — tout endpoint texte libre doit être couvert.</p>
 *
 * <p><b>Formats acceptés</b> : texte brut (y compris Markdown — c'est juste du texte, aucun
 * traitement spécial requis) et PDF. <b>HTML explicitement refusé</b> (voir {@link #MARKUP_PATTERN})
 * — surface d'attaque disproportionnée (scripts, attributs {@code on*=}, obfuscation d'injection
 * via balises) pour un format que personne n'utilise réellement pour rédiger une politique.</p>
 */
@RestController
@RequestMapping("/settings/policies")
@RequiredArgsConstructor
@Slf4j
public class PolicyController {

    private static final String EXPENSE_REIMBURSEMENT = "EXPENSE_REIMBURSEMENT";

    // HTML/scripts explicitement refusés comme format de politique (voir javadoc de classe) — sert
    // aussi de défense en profondeur contre une tentative d'injection découpée par des balises pour
    // échapper à la détection par motifs de PromptGuard (ex: "ignore<b></b> les instructions").
    private static final Pattern MARKUP_PATTERN = Pattern.compile(
            "<\\s*(script|iframe|object|embed|style|img)\\b|on\\w+\\s*=|javascript\\s*:",
            Pattern.CASE_INSENSITIVE);

    private final PolicyIngestionService policyService;
    private final PolicyPdfExtractionService pdfExtractionService;
    private final PolicyPdfRenderingService pdfRenderingService;
    private final PolicyFileStorageService fileStorage;
    private final PromptGuard promptGuard;
    private final io.multiagent.core.i18n.Messages messages;

    @Value("${ai-core.guard.policy-max-chars:20000}")
    private int policyMaxChars;

    @PostMapping("/expense-reimbursement")
    public ResponseEntity<Object> submit(@RequestBody Map<String, String> body, Authentication authentication) {
        String text = body.get("policyText");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", messages.get("policy.error.textRequired")));
        }
        if (containsMarkup(text)) {
            return ResponseEntity.badRequest().body(Map.of("error", messages.get("policy.error.markup")));
        }
        String user = currentUser(authentication);
        PromptGuard.GuardResult guard = promptGuard.check(text, user, policyMaxChars);
        if (!guard.allowed()) {
            return ResponseEntity.status(429).body(Map.of("error", guard.rejectionReason()));
        }
        String title = body.getOrDefault("title", messages.getDefault("policy.defaultTitle"));
        try {
            PolicyDocumentEntity policy = policyService.ingest(
                    EXPENSE_REIMBURSEMENT, title, guard.sanitizedText(), user);
            return ResponseEntity.ok(toResponse(policy));
        } catch (Exception e) {
            log.error("policy submit error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Import PDF — mêmes garanties que {@link #submit} (PromptGuard explicite, jamais d'activation
     * automatique) mais sur un texte extrait d'un document "born-digital" via PDFBox plutôt que
     * saisi. Endpoint multipart exclu de {@link io.multiagent.core.security.PromptGuardFilter}
     * (binaire — cas légitime documenté dans CLAUDE.md), la vérification se fait ici sur le texte
     * extrait, pas sur les octets bruts du fichier.
     */
    @PostMapping(value = "/expense-reimbursement/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Object> submitPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            Authentication authentication) {
        String user = currentUser(authentication);
        String extractedText;
        try {
            extractedText = pdfExtractionService.extractText(file);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        if (containsMarkup(extractedText)) {
            return ResponseEntity.badRequest().body(Map.of("error", messages.get("policy.error.markup")));
        }
        PromptGuard.GuardResult guard = promptGuard.check(extractedText, user, policyMaxChars);
        if (!guard.allowed()) {
            return ResponseEntity.status(429).body(Map.of("error", guard.rejectionReason()));
        }
        try {
            PolicyDocumentEntity policy = policyService.ingestPdf(
                    EXPENSE_REIMBURSEMENT,
                    (title != null && !title.isBlank()) ? title : messages.getDefault("policy.defaultTitlePdf"),
                    guard.sanitizedText(), user, file);
            return ResponseEntity.ok(toResponse(policy));
        } catch (Exception e) {
            log.error("policy submit pdf error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/expense-reimbursement/{id}/activate")
    public ResponseEntity<Object> activate(@PathVariable UUID id) {
        try {
            PolicyDocumentEntity policy = policyService.activate(id);
            return ResponseEntity.ok(toResponse(policy));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("policy activate error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/expense-reimbursement/active")
    public ResponseEntity<Object> active() {
        return policyService.findActivePolicy(EXPENSE_REIMBURSEMENT)
                .<ResponseEntity<Object>>map(p -> ResponseEntity.ok(toResponse(p)))
                .orElseGet(() -> ResponseEntity.ok(Map.of("status", "NONE")));
    }

    /** Historique complet des versions (DRAFT/ACTIVE/ARCHIVED), la plus récente en premier. */
    @GetMapping("/expense-reimbursement")
    public ResponseEntity<Object> list() {
        List<Map<String, Object>> versions = policyService.listVersions(EXPENSE_REIMBURSEMENT)
                .stream().map(PolicyController::toResponse).toList();
        return ResponseEntity.ok(Map.of("versions", versions));
    }

    /**
     * Télécharge la politique au format PDF — le fichier original si elle a été importée en PDF
     * (fidélité exacte), sinon un PDF généré à la volée depuis le texte saisi (voir
     * {@link PolicyPdfRenderingService}).
     */
    @GetMapping("/expense-reimbursement/{id}/pdf")
    public ResponseEntity<byte[]> downloadPdf(@PathVariable UUID id) {
        PolicyDocumentEntity policy = policyService.findById(id).orElse(null);
        if (policy == null) return ResponseEntity.notFound().build();

        byte[] pdfBytes;
        if (policy.getSourceFilePath() != null && Files.exists(Path.of(policy.getSourceFilePath()))) {
            pdfBytes = fileStorage.read(policy.getSourceFilePath());
        } else {
            pdfBytes = pdfRenderingService.render(
                    policy.getTitle() != null ? policy.getTitle() : messages.getDefault("policy.defaultTitle"),
                    policy.getVersion(), policy.getRawText());
        }

        String filename = messages.get("policy.filename") + "-v" + policy.getVersion() + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(pdfBytes);
    }

    private static boolean containsMarkup(String text) {
        return text != null && MARKUP_PATTERN.matcher(text).find();
    }

    private static Map<String, Object> toResponse(PolicyDocumentEntity p) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", p.getId());
        m.put("policyType", p.getPolicyType());
        m.put("title", p.getTitle() != null ? p.getTitle() : "");
        m.put("version", p.getVersion());
        m.put("status", p.getStatus());
        m.put("rawText", p.getRawText());
        m.put("createdAt", p.getCreatedAt() != null ? p.getCreatedAt().toString() : null);
        m.put("activatedAt", p.getActivatedAt() != null ? p.getActivatedAt().toString() : null);
        return m;
    }

    private static String currentUser(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            String email = jwt.getClaimAsString("email");
            if (email != null && !email.isBlank()) return email;
            return jwt.getClaimAsString("preferred_username");
        }
        return authentication != null ? authentication.getName() : null;
    }
}
