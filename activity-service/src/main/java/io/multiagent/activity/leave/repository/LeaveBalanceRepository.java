package io.multiagent.activity.leave.repository;

import io.multiagent.activity.leave.entity.LeaveBalanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LeaveBalanceRepository extends JpaRepository<LeaveBalanceEntity, UUID> {
    Optional<LeaveBalanceEntity> findByTenantIdAndConsultantEmailIgnoreCaseAndYear(UUID tenantId, String email, int year);
}
