package io.multiagent.core.guard;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Réponse de l'API LLM Guard pour un scan de prompt ou de sortie LLM.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmGuardScanResult(
        @JsonProperty("is_valid") boolean isValid,
        @JsonProperty("sanitized_prompt") String sanitizedPrompt,
        @JsonProperty("scanners") Map<String, ScannerDetail> scanners
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScannerDetail(
            @JsonProperty("is_valid") boolean isValid,
            @JsonProperty("score") double score
    ) {}

    /** Retourne le scanner qui a échoué avec le score le plus haut. */
    public String worstScanner() {
        if (scanners == null || scanners.isEmpty()) return "unknown";
        return scanners.entrySet().stream()
                .filter(e -> !e.getValue().isValid())
                .max(java.util.Comparator.comparingDouble(e -> e.getValue().score()))
                .map(Map.Entry::getKey)
                .orElse("unknown");
    }
}
