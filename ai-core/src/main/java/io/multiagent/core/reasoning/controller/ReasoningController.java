package io.multiagent.core.reasoning.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.multiagent.core.model.ReasoningResult;
import io.multiagent.core.reasoning.service.ReasoningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/reasoning")
public class ReasoningController {

    private static final String FIELD_INTENT = "intent";

    private final ReasoningService reasoningService;
    private final ObjectMapper objectMapper;

    @PostMapping("/analyze")
    public ReasoningResult analyze(
            @RequestBody String payload,
            @RequestHeader(value = "X-Source", required = false) String source) {
        String normalizedPayload = normalizePayload(payload);
        String consultantEmail = extractField(payload, "consultantEmail");
        log.info("➡️ AI-Core /reasoning/analyze received payload ({} chars) normalized to {} chars, consultant={}",
                payload.length(), normalizedPayload.length(), maskEmail(consultantEmail));

        // Guard appliqué globalement par PromptGuardFilter avant d'atteindre ce controller
        if ("web-ui".equals(source)) {
            String intent = detectDeleteIntent(normalizedPayload);
            if (intent != null) {
                log.warn("🚫 Suppression bloquée depuis l'interface web (intent={})", intent);
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Les suppressions ne sont pas autorisées depuis l'interface web. Utilisez le terminal.");
            }
        }

        ReasoningResult result = reasoningService.process(normalizedPayload, consultantEmail);
        try {
            log.info("✅ /reasoning/analyze result: {}", objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            log.info("✅ /reasoning/analyze result: type={} status={} expenses={}",
                    result.getType(), result.getStatus(),
                    result.getExpenses() != null ? result.getExpenses().size() : 0);
        }
        return result;
    }

    /**
     * Détecte rapidement un intent de suppression sans appel LLM.
     * Retourne le nom de l'intent si c'est un delete, null sinon.
     */
    private String detectDeleteIntent(String payload) {
        if (payload == null || payload.isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (node.has(FIELD_INTENT)) {
                String intent = node.get(FIELD_INTENT).asText("");
                if (intent.startsWith("delete_")) return intent;
            }
        } catch (Exception ignored) { /* payload texte brut */ }

        String lower = payload.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("supprim") || lower.contains("retir") || lower.contains("efface")
                || lower.contains("suppr") || lower.contains("delete") || lower.contains("remov")) {
            return "delete_suspected";
        }
        return null;
    }

    private String extractField(String payload, String field) {
        if (payload == null || payload.isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (node.hasNonNull(field)) {
                return node.get(field).asText(null);
            }
        } catch (Exception ignored) { /* payload texte brut */ }
        return null;
    }

    private static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) return "***" + domain;
        return local.charAt(0) + "***" + local.charAt(local.length() - 1) + domain;
    }

    private String normalizePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (!node.isObject()) {
                return payload;
            }
            // Cas 1: requête simple de type {"text":"..."} -> on extrait le texte métier.
            if (node.hasNonNull("text") && !node.has(FIELD_INTENT)) {
                return node.get("text").asText(payload);
            }
            // Cas 2: JSON d'intent déjà structuré -> on le garde tel quel pour préserver entities/ids/month.
            return payload;
        } catch (Exception ignored) {
            return payload;
        }
    }
}
