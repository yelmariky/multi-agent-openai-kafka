package io.multiagent.core.settings.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "seller_profile", uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id"}))
@Getter
@Setter
@NoArgsConstructor
public class SellerProfileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
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
