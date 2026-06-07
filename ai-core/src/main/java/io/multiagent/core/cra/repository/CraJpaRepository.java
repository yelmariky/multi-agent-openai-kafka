package io.multiagent.core.cra.repository;

import io.multiagent.core.cra.entity.CraEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CraJpaRepository extends JpaRepository<CraEntity, UUID> {

    List<CraEntity> findByTenantId(UUID tenantId);

    List<CraEntity> findByTenantIdAndBillingMonthBetween(UUID tenantId, String start, String end);

    List<CraEntity> findByTenantIdAndConsultantContainingIgnoreCase(UUID tenantId, String consultant);

    List<CraEntity> findByTenantIdAndBillingMonth(UUID tenantId, String billingMonth);

    List<CraEntity> findByTenantIdAndBillingMonthAndConsultantContainingIgnoreCase(
            UUID tenantId, String billingMonth, String consultant);
}
