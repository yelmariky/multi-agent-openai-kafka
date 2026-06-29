package io.multiagent.core.organization.repository;

import io.multiagent.core.organization.entity.Resource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResourceRepository extends JpaRepository<Resource, UUID> {
    List<Resource> findByTenantId(UUID tenantId);
    List<Resource> findByTenantIdAndActiveTrue(UUID tenantId);
    Optional<Resource> findByTenantIdAndEmail(UUID tenantId, String email);
}
