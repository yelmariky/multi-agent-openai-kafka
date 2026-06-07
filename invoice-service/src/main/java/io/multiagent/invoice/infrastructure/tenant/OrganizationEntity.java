package io.multiagent.invoice.infrastructure.tenant;

import jakarta.persistence.*;
import lombok.Getter;

import java.util.UUID;

/**
 * Read-only projection of the organization table — used solely by TenantFilter
 * to resolve tenant UUID from Keycloak realm name.
 */
@Entity
@Table(name = "organization")
@Getter
public class OrganizationEntity {

    @Id
    private UUID id;

    @Column(name = "keycloak_realm", unique = true, nullable = false)
    private String keycloakRealm;
}
