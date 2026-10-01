package io.multiagent.core.invoice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Lecture seule de la table {@code invoice} — schéma possédé par invoice-service mais géré par
 * Flyway côté ai-service (DB partagée, invoice-service tourne avec flyway.enabled=false). Utilisé
 * uniquement par les outils MCP en lecture ; aucune écriture ne doit passer par cette entité —
 * la création/modification de factures reste la responsabilité exclusive d'invoice-service.
 */
@Entity
@Table(name = "invoice")
@Getter
@Setter
@NoArgsConstructor
public class InvoiceEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "invoice_name", length = 100)
    private String invoiceName;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "billing_month", length = 7)
    private String billingMonth;

    @Column(name = "client_company_name")
    private String clientCompanyName;

    @Column(name = "consultant_email")
    private String consultantEmail;

    @Column(name = "total_ht")
    private BigDecimal totalHt;

    @Column(name = "total_ttc")
    private BigDecimal totalTtc;

    @Column(length = 10)
    private String currency = "EUR";

    @Column(name = "payment_due_date")
    private LocalDate paymentDueDate;

    @Column(name = "payment_status", length = 20)
    private String paymentStatus = "EN_ATTENTE";

    @Column(name = "payment_received_date")
    private LocalDate paymentReceivedDate;

    @Column(name = "sent_date")
    private LocalDate sentDate;

    @Column(name = "invoice_number", length = 50)
    private String invoiceNumber;

    @Column(name = "created_at")
    private Instant createdAt;
}
