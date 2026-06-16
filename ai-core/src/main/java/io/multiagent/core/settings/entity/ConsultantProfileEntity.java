package io.multiagent.core.settings.entity;

import io.multiagent.core.organization.entity.ProjectEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "consultant_profile", uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "email"}))
@Getter
@Setter
@NoArgsConstructor
public class ConsultantProfileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String email;

    private String name;

    @Column(length = 100)
    private String role;

    private String company;

    @Column(name = "client_name")
    private String clientName;

    @Column(name = "client_address", columnDefinition = "TEXT")
    private String clientAddress;

    @Column(name = "client_rcs", length = 100)
    private String clientRcs;

    @Column(name = "client_contact_email")
    private String clientContactEmail;

    private BigDecimal tjm;

    private Boolean active = true;

    @Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "vehicle_type", length = 20)
    private io.multiagent.core.model.VehicleType vehicleType = io.multiagent.core.model.VehicleType.CAR;

    @Column(name = "fiscal_power")
    private Integer fiscalPower = 7;

    @Column(name = "km_annual")
    private Integer kmAnnual = 4999;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "consultant_project",
        joinColumns = @JoinColumn(name = "consultant_profile_id"),
        inverseJoinColumns = @JoinColumn(name = "project_id")
    )
    private List<ProjectEntity> projects = new ArrayList<>();
}
