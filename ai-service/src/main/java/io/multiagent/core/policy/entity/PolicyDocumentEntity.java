package io.multiagent.core.policy.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "policy_document")
@Getter
@Setter
@NoArgsConstructor
public class PolicyDocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policy_type", nullable = false, length = 50)
    private String policyType;

    @Column(length = 255)
    private String title;

    @Column(nullable = false)
    private Integer version;

    @Column(nullable = false, length = 20)
    private String status = "DRAFT";

    @Column(name = "raw_text", columnDefinition = "TEXT", nullable = false)
    private String rawText;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    /** Chemin du PDF original (politiques importées en PDF) — null si saisie en texte libre. */
    @Column(name = "source_file_path", length = 500)
    private String sourceFilePath;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
