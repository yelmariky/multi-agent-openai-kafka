package io.multiagent.invoice.entity;

import jakarta.persistence.*;
import lombok.Getter;

import java.util.UUID;

/**
 * Read-only projection of the seller_profile table managed by ai-core.
 * Used by InvoiceService to fetch seller info (address, IBAN, etc.).
 */
@Entity
@Table(name = "seller_profile")
@Getter
public class SellerProfileEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(length = 100)
    private String rcs;

    @Column(length = 50)
    private String iban;

    @Column(length = 20)
    private String bic;

    private String email;

    @Column(length = 100)
    private String capital;

    @Column(name = "late_payment_clause", columnDefinition = "TEXT")
    private String latePaymentClause;
}
