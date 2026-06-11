package io.multiagent.core.organization.repository;

import io.multiagent.core.organization.entity.ProjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<ProjectEntity, UUID> {
    List<ProjectEntity> findByTenantId(UUID tenantId);
}
