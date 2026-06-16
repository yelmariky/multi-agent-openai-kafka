package io.multiagent.core.organization.repository;

import io.multiagent.core.organization.entity.ConsultantAssignmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConsultantAssignmentRepository extends JpaRepository<ConsultantAssignmentEntity, UUID> {

    List<ConsultantAssignmentEntity> findByConsultantProfileIdAndTenantId(UUID consultantProfileId, UUID tenantId);

    Optional<ConsultantAssignmentEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    void deleteByIdAndTenantId(UUID id, UUID tenantId);
}
