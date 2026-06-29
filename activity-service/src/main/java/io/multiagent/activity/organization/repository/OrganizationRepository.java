package io.multiagent.activity.organization.repository;

import io.multiagent.activity.organization.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
    Optional<Organization> findBySlug(String slug);
    Optional<Organization> findByKeycloakRealm(String keycloakRealm);
    boolean existsBySlug(String slug);
}
