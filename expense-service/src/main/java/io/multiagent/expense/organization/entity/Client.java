package io.multiagent.expense.organization.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.UUID;

@Entity
@Table(name = "client")
@Getter @Setter @NoArgsConstructor
public class Client {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    private String address;
    private String rcs;
    private Boolean active = true;

    @Column(name = "payment_terms_days")
    private Integer paymentTermsDays = 30;
}
