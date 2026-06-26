package io.multiagent.core.dashboard.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Vue en lecture seule de la table invoice pour les métriques dashboard.
 * invoice-service est le seul writer ; ai-core lit uniquement.
 */
@Entity
@Table(name = "invoice")
@Getter
@NoArgsConstructor
public class InvoiceSummaryEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "total_ttc")
    private BigDecimal totalTtc;

    @Column(name = "payment_due_date")
    private LocalDate paymentDueDate;

    @Column(name = "payment_status", length = 20)
    private String paymentStatus;

    @Column(name = "payment_received_date")
    private LocalDate paymentReceivedDate;

    @Column(name = "consultant_email")
    private String consultantEmail;

    @Column(name = "billing_month", length = 7)
    private String billingMonth;

    @Column(name = "invoice_number", length = 50)
    private String invoiceNumber;

    @Column(name = "sent_date")
    private LocalDate sentDate;
}
