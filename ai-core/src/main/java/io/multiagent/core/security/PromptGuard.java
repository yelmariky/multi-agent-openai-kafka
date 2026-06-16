package io.multiagent.core.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Couche de sécurité centralisée pour les entrées LLM.
 *
 * Protège contre :
 * - Prompt injection (patterns connus)
 * - Flooding de tokens (limite de taille)
 * - Abus répétés (rate limiting par consultant)
 * - Caractères de contrôle / null bytes
 */
@Slf4j
@Component
public class PromptGuard {

    // ── Limites configurables ──────────────────────────────────────────────────
    @Value("${ai-core.guard.max-chars:4000}")
    private int maxChars;

    @Value("${ai-core.guard.max-requests-per-minute:20}")
    private int maxRequestsPerMinute;

    // ── Rate limiter : clé = email consultant, valeur = nb req dans la minute ─
    private final Cache<String, AtomicInteger> rateLimitCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();

    // ── Patterns d'injection connus ────────────────────────────────────────────
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            // Annulation d'instructions
            Pattern.compile("ignore (all )?(previous|prior|above|the) instructions?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("forget (everything|all|the rules|your instructions?)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard (all )?(previous|your|the) instructions?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("override (the )?(system|previous) (prompt|instructions?)", Pattern.CASE_INSENSITIVE),

            // Hijacking de rôle
            Pattern.compile("you are now (a |an )?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("act as (a |an )?(different|new|another|evil|malicious)?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("pretend (to be|you are|you're)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("from now on (you are|act|behave)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("your new (role|persona|identity|task|goal) is", Pattern.CASE_INSENSITIVE),

            // Injection de structure (séparateurs de rôle pour GPT / Llama / Claude)
            Pattern.compile("(?m)^\\s*(system|assistant|human|user)\\s*:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\[INST\\]|\\[/INST\\]|<\\|system\\|>|<\\|user\\|>|<\\|assistant\\|>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("###\\s*(instruction|system|context|response|input)", Pattern.CASE_INSENSITIVE),

            // Exfiltration de données
            Pattern.compile("(show|list|print|display|output|reveal|dump) (all|every|the) (user|consultant|expense|invoice|data|record)s?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(give me|send me|return) (all|every) (data|record|user|expense)", Pattern.CASE_INSENSITIVE),

            // Tentatives de jailbreak classiques
            Pattern.compile("do anything now|DAN mode|developer mode|jailbreak", Pattern.CASE_INSENSITIVE),
            Pattern.compile("without (any )?(restrictions?|limitations?|filters?|safety)", Pattern.CASE_INSENSITIVE)
    );

    // ── API publique ──────────────────────────────────────────────────────────

    /**
     * Vérifie et assainit le texte utilisateur avant tout appel LLM.
     *
     * @param text            texte brut fourni par le consultant
     * @param consultantEmail email pour le rate limiting (peut être null)
     * @return GuardResult.allow(sanitizedText) ou GuardResult.reject(reason)
     */
    public GuardResult check(String text, String consultantEmail) {

        // 1. Taille maximale — protège contre le token flooding
        if (text != null && text.length() > maxChars) {
            log.warn("🛡️ [GUARD] Texte trop volumineux : {} chars (max {}) — consultant={}",
                    text.length(), maxChars, maskEmail(consultantEmail));
            return GuardResult.reject(
                    "Votre message est trop long (" + text.length() + " caractères, maximum " + maxChars + ").");
        }

        // 2. Rate limiting par consultant
        if (consultantEmail != null && !consultantEmail.isBlank()) {
            AtomicInteger counter = rateLimitCache.get(consultantEmail, k -> new AtomicInteger(0));
            int current = counter.incrementAndGet();
            if (current > maxRequestsPerMinute) {
                log.warn("🛡️ [GUARD] Rate limit dépassé : {}/min pour {}", current, maskEmail(consultantEmail));
                return GuardResult.reject(
                        "Trop de requêtes envoyées. Merci de patienter quelques secondes avant de réessayer.");
            }
        }

        // 3. Détection de prompt injection
        if (text != null) {
            for (Pattern pattern : INJECTION_PATTERNS) {
                if (pattern.matcher(text).find()) {
                    log.warn("🛡️ [GUARD] Prompt injection détectée (pattern='{}') — consultant={} — texte=[{}]",
                            pattern.pattern(), maskEmail(consultantEmail), truncate(text, 120));
                    return GuardResult.reject(
                            "Contenu non autorisé détecté. Merci de saisir uniquement vos notes de frais.");
                }
            }
        }

        // 4. Suppression des caractères dangereux (null bytes, chars de contrôle non-imprimables)
        //    On conserve \t (tabulation) et \n (saut de ligne) qui sont légitimes dans du texte.
        String sanitized = text == null ? ""
                : text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F\uFEFF\u200B\u200C\u200D\u2060]", "")
                      .trim();

        return GuardResult.allow(sanitized);
    }

    // ── Résultat immuable ─────────────────────────────────────────────────────

    public record GuardResult(boolean allowed, String sanitizedText, String rejectionReason) {
        public static GuardResult allow(String text) {
            return new GuardResult(true, text, null);
        }
        public static GuardResult reject(String reason) {
            return new GuardResult(false, null, reason);
        }
    }

    // ── Helpers privés ────────────────────────────────────────────────────────

    private static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) return "***" + domain;
        return local.charAt(0) + "***" + local.charAt(local.length() - 1) + domain;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
