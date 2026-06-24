package io.multiagent.core.governance;

import lombok.Builder;

/**
 * Contexte d'un appel LLM passé au service d'audit.
 */
@Builder
public record LlmCallContext(
        String userEmail,
        String feature,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        String inputText,       // sera hashé SHA-256, jamais stocké brut
        String responseSummary,
        String aiFlags,         // JSON ex: {"montant_eleve":true}
        Double confidenceScore,
        Integer durationMs,
        boolean success,
        String errorMessage
) {}
