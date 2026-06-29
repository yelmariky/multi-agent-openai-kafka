package io.multiagent.core.leave.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "leave_balance",
       uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "consultant_email", "year"}))
@Getter @Setter @NoArgsConstructor
public class LeaveBalanceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "consultant_email", nullable = false, length = 200)
    private String consultantEmail;

    @Column(nullable = false)
    private Integer year;

    @Column(name = "cp_initial")
    private BigDecimal cpInitial = BigDecimal.valueOf(25.0);

    @Column(name = "cp_taken")
    private BigDecimal cpTaken = BigDecimal.ZERO;

    @Column(name = "rtt_initial")
    private BigDecimal rttInitial = BigDecimal.valueOf(12.0);

    @Column(name = "rtt_taken")
    private BigDecimal rttTaken = BigDecimal.ZERO;
}
