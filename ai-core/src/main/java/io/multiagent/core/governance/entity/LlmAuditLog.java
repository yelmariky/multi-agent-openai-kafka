package io.multiagent.core.governance.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "llm_audit_log")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "user_email", length = 255)
    private String userEmail;

    @Column(name = "feature", nullable = false, length = 100)
    private String feature;

    @Column(name = "model", nullable = false, length = 100)
    private String model;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    // SHA-256 du prompt — jamais le texte brut (RGPD)
    @Column(name = "input_hash", length = 64)
    private String inputHash;

    @Column(name = "response_summary", columnDefinition = "TEXT")
    private String responseSummary;

    // JSON des signaux détectés ex: {"montant_eleve":true,"date_future":false}
    @Column(name = "ai_flags", columnDefinition = "TEXT")
    private String aiFlags;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
