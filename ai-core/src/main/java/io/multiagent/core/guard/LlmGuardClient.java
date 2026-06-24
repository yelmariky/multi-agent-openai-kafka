package io.multiagent.core.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Client HTTP vers l'API LLM Guard (Protect AI).
 *
 * Comportement dégradé : si LLM Guard est indisponible (dev local, timeout),
 * les appels passent sans blocage — l'audit reste tracé via LlmAuditService.
 */
@Slf4j
@Component
public class LlmGuardClient {

    private static final List<String> INPUT_SCANNERS  = List.of("PromptInjection", "Secrets", "TokenLimit", "Language");
    private static final List<String> OUTPUT_SCANNERS = List.of("Relevance", "Sensitive", "NoRefusal");

    private final boolean enabled;
    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public LlmGuardClient(
            @Value("${llmguard.enabled:false}") boolean enabled,
            @Value("${llmguard.url:http://llm-guard:8000}") String baseUrl,
            @Value("${llmguard.timeout-ms:2000}") long timeoutMs,
            ObjectMapper mapper) {
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.mapper  = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();

        if (enabled) {
            log.info("LlmGuardClient enabled — url={}", baseUrl);
        } else {
            log.info("LlmGuardClient disabled (set llmguard.enabled=true to activate)");
        }
    }

    /**
     * Scanne un prompt avant envoi à OpenAI.
     * Lève {@link LlmGuardBlockedException} si une menace est détectée.
     */
    public void scanPrompt(String prompt) {
        if (!enabled || prompt == null || prompt.isBlank()) return;
        LlmGuardScanResult result = callApi("/analyze/prompt", Map.of(
                "prompt", prompt,
                "scanners", INPUT_SCANNERS
        ));
        if (result != null && !result.isValid()) {
            String scanner = result.worstScanner();
            double score   = result.scanners() != null && result.scanners().containsKey(scanner)
                    ? result.scanners().get(scanner).score() : 1.0;
            log.warn("LLM Guard BLOCKED prompt — scanner={} score={}", scanner, score);
            throw new LlmGuardBlockedException(scanner, score,
                    "Prompt bloqué par LLM Guard (scanner=" + scanner + ", score=" + score + ")");
        }
    }

    /**
     * Scanne la réponse LLM après réception.
     * Ne bloque pas — logue seulement un avertissement pour ne pas perdre le résultat.
     *
     * @return la réponse (potentiellement sanitisée si LLM Guard a modifié la sortie)
     */
    public String scanOutput(String prompt, String output) {
        if (!enabled || output == null || output.isBlank()) return output;
        LlmGuardScanResult result = callApi("/analyze/output", Map.of(
                "prompt", prompt,
                "output", output,
                "scanners", OUTPUT_SCANNERS
        ));
        if (result != null && !result.isValid()) {
            log.warn("LLM Guard flagged output — scanner={} (non-blocking)",
                    result.worstScanner());
        }
        // Retourne la sortie sanitisée si disponible, sinon l'originale
        if (result != null && result.sanitizedPrompt() != null && !result.sanitizedPrompt().isBlank()) {
            return result.sanitizedPrompt();
        }
        return output;
    }

    public boolean isEnabled() {
        return enabled;
    }

    private LlmGuardScanResult callApi(String path, Map<String, Object> body) {
        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofMillis(2000))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("LLM Guard returned HTTP {} for {} — skipping scan", response.statusCode(), path);
                return null;
            }
            return mapper.readValue(response.body(), LlmGuardScanResult.class);
        } catch (java.net.http.HttpTimeoutException e) {
            log.warn("LLM Guard timeout on {} — scan skipped (degraded mode)", path);
            return null;
        } catch (Exception e) {
            log.warn("LLM Guard unavailable ({}) — scan skipped: {}", path, e.getMessage());
            return null;
        }
    }
}
