package io.multiagent.core.cra.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cra")
@Getter
@Setter
@NoArgsConstructor
public class CraEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String consultant;

    private String company;

    @Column(name = "client_company")
    private String clientCompany;

    @Column(name = "billing_month", nullable = false, length = 7)
    private String billingMonth;

    @Column(name = "entries_json", columnDefinition = "TEXT")
    private String entriesJson;

    @Column(name = "total_days")
    private BigDecimal totalDays;

    @Column(length = 50)
    private String status = "BROUILLON";

    @Column(name = "submitted_at", length = 50)
    private String submittedAt;

    @Column(name = "validated_at", length = 50)
    private String validatedAt;

    @Column(name = "validated_by")
    private String validatedBy;

    @Column(name = "refused_reason", columnDefinition = "TEXT")
    private String refusedReason;

    @Column(name = "mission_id")
    private UUID missionId;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
