package io.multiagent.invoice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "invoice")
@Getter
@Setter
@NoArgsConstructor
public class InvoiceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "invoice_name", length = 100)
    private String invoiceName;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "billing_month", length = 7)
    private String billingMonth;

    @Column(name = "seller_company_name")
    private String sellerCompanyName;

    @Column(name = "seller_address", columnDefinition = "TEXT")
    private String sellerAddress;

    @Column(name = "seller_rcs", length = 100)
    private String sellerRcs;

    @Column(name = "client_company_name")
    private String clientCompanyName;

    @Column(name = "client_address", columnDefinition = "TEXT")
    private String clientAddress;

    @Column(name = "client_rcs", length = 100)
    private String clientRcs;

    @Column(name = "invoice_title")
    private String invoiceTitle;

    @Column(name = "days_count")
    private Integer daysCount;

    @Column(name = "days_exact")
    private BigDecimal daysExact;

    @Column(name = "unit_price_ht")
    private BigDecimal unitPriceHt;

    @Column(name = "total_ht")
    private BigDecimal totalHt;

    @Column(name = "vat_rate")
    private BigDecimal vatRate;

    @Column(name = "total_ttc")
    private BigDecimal totalTtc;

    @Column(length = 10)
    private String currency = "EUR";

    @Column(name = "payment_due_date")
    private LocalDate paymentDueDate;

    @Column(name = "late_payment_clause", columnDefinition = "TEXT")
    private String latePaymentClause;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "consultant_email")
    private String consultantEmail;

    @Column(name = "mission_id")
    private UUID missionId;

    @Column(name = "source_text", columnDefinition = "TEXT")
    private String sourceText;

    @Column(name = "pdf_path", length = 500)
    private String pdfPath;

    @Column(name = "excel_path", length = 500)
    private String excelPath;

    // Suivi paiement
    @Column(name = "payment_status", length = 20)
    private String paymentStatus = "EN_ATTENTE"; // EN_ATTENTE | ENVOYEE | PAYEE | EN_RETARD

    @Column(name = "payment_received_date")
    private LocalDate paymentReceivedDate;

    @Column(name = "sent_date")
    private LocalDate sentDate;

    @Column(name = "invoice_number", length = 50)
    private String invoiceNumber;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (paymentStatus == null) paymentStatus = "EN_ATTENTE";
    }
}
