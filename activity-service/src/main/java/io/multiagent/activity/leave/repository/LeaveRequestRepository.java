package io.multiagent.activity.leave.repository;

import io.multiagent.activity.leave.entity.LeaveRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequestEntity, UUID> {
    List<LeaveRequestEntity> findByTenantId(UUID tenantId);
    List<LeaveRequestEntity> findByTenantIdAndStatus(UUID tenantId, String status);
    List<LeaveRequestEntity> findByTenantIdAndConsultantEmailIgnoreCase(UUID tenantId, String email);
    List<LeaveRequestEntity> findByTenantIdAndConsultantEmailIgnoreCaseAndStatus(UUID tenantId, String email, String status);
}
