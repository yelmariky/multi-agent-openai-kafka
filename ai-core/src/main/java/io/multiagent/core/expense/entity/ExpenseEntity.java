package io.multiagent.core.expense.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "expense")
@Getter
@Setter
@NoArgsConstructor
public class ExpenseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "expense_id")
    private Integer expenseId;

    @Column(name = "consultant_email")
    private String consultantEmail;

    private BigDecimal amount;

    @Column(length = 10)
    private String currency = "EUR";

    @Column(length = 100)
    private String type;

    private BigDecimal km;

    @Column(name = "expense_date")
    private LocalDate expenseDate;

    @Column(name = "date_text", length = 50)
    private String dateText;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "original_text", columnDefinition = "TEXT")
    private String originalText;

    @Column(length = 50)
    private String source;

    @Column(name = "payment_mode", length = 50)
    private String paymentMode;

    @Column(columnDefinition = "TEXT")
    private String address;

    private String company;

    @Column(name = "duplicate_flag")
    private Boolean duplicateFlag = false;

    private String hash;

    @Column(name = "approval_status", length = 50)
    private String approvalStatus = "PENDING";

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approval_note", columnDefinition = "TEXT")
    private String approvalNote;

    @Column(name = "receipt_path", length = 500)
    private String receiptPath;

    @Column(name = "absence_periods_json", columnDefinition = "TEXT")
    private String absencePeriodsJson;

    @Column(name = "source_text", columnDefinition = "TEXT")
    private String sourceText;

    @Column(name = "created_at")
    private Instant createdAt;

    // --- Gouvernance IA ---
    @Column(name = "ai_confidence_score")
    private Double aiConfidenceScore;

    @Column(name = "ai_flags", columnDefinition = "TEXT")
    private String aiFlags;

    @Column(name = "ai_review_required", nullable = false)
    private boolean aiReviewRequired = false;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
