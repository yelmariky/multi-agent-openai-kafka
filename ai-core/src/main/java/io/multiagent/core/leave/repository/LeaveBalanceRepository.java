package io.multiagent.core.leave.repository;

import io.multiagent.core.leave.entity.LeaveBalanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LeaveBalanceRepository extends JpaRepository<LeaveBalanceEntity, UUID> {
    Optional<LeaveBalanceEntity> findByTenantIdAndConsultantEmailIgnoreCaseAndYear(UUID tenantId, String email, int year);
}
