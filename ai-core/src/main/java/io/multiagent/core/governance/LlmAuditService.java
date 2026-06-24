package io.multiagent.core.governance;

import io.multiagent.core.governance.entity.LlmAuditLog;
import io.multiagent.core.governance.repository.LlmAuditLogRepository;
import io.multiagent.core.infrastructure.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Enregistre chaque appel LLM pour auditabilité et gouvernance.
 * Les appels sont asynchrones pour ne pas impacter la latence métier.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmAuditService {

    private final LlmAuditLogRepository repository;

    @Async
    public void record(LlmCallContext ctx) {
        try {
            LlmAuditLog entry = LlmAuditLog.builder()
                    .tenantId(TenantContext.getTenantIdOrNull())
                    .userEmail(ctx.userEmail())
                    .feature(ctx.feature())
                    .model(ctx.model())
                    .promptTokens(ctx.promptTokens())
                    .completionTokens(ctx.completionTokens())
                    .totalTokens(safeSum(ctx.promptTokens(), ctx.completionTokens()))
                    .inputHash(sha256(ctx.inputText()))
                    .responseSummary(truncate(ctx.responseSummary(), 500))
                    .aiFlags(ctx.aiFlags())
                    .confidenceScore(ctx.confidenceScore())
                    .durationMs(ctx.durationMs())
                    .success(ctx.success())
                    .errorMessage(ctx.errorMessage())
                    .build();

            repository.save(entry);
        } catch (Exception e) {
            // Ne jamais faire échouer le flux métier à cause de l'audit
            log.warn("LLM audit save failed (non-blocking): {}", e.getMessage());
        }
    }

    /** Enregistre un appel réussi avec le minimum d'infos. */
    @Async
    public void recordSuccess(String feature, String model, String inputText,
                              String responseSummary, int durationMs) {
        record(LlmCallContext.builder()
                .feature(feature)
                .model(model)
                .inputText(inputText)
                .responseSummary(responseSummary)
                .durationMs(durationMs)
                .success(true)
                .build());
    }

    /** Enregistre un échec LLM. */
    @Async
    public void recordFailure(String feature, String model, String errorMessage, int durationMs) {
        record(LlmCallContext.builder()
                .feature(feature)
                .model(model)
                .durationMs(durationMs)
                .success(false)
                .errorMessage(truncate(errorMessage, 500))
                .build());
    }

    private static String sha256(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static Integer safeSum(Integer a, Integer b) {
        if (a == null && b == null) return null;
        return (a == null ? 0 : a) + (b == null ? 0 : b);
    }
}
