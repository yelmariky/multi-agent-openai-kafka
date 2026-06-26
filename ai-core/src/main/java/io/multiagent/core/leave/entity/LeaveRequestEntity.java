package io.multiagent.core.leave.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "leave_request")
@Getter @Setter @NoArgsConstructor
public class LeaveRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "consultant_email", nullable = false, length = 200)
    private String consultantEmail;

    /** CP | RTT | MALADIE | FORMATION | AUTRE */
    @Column(nullable = false, length = 30)
    private String type;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "days_count", nullable = false)
    private BigDecimal daysCount;

    /** DEMANDEE | APPROUVEE | REFUSEE */
    @Column(nullable = false, length = 20)
    private String status = "DEMANDEE";

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "refused_reason", columnDefinition = "TEXT")
    private String refusedReason;

    @Column(name = "approved_by", length = 200)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = "DEMANDEE";
    }
}
