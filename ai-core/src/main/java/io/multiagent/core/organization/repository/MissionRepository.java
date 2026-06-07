package io.multiagent.core.organization.repository;

import io.multiagent.core.organization.entity.Mission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MissionRepository extends JpaRepository<Mission, UUID> {
    List<Mission> findByTenantId(UUID tenantId);
    List<Mission> findByTenantIdAndStatus(UUID tenantId, String status);
    List<Mission> findByTenantIdAndResourceId(UUID tenantId, UUID resourceId);
}
