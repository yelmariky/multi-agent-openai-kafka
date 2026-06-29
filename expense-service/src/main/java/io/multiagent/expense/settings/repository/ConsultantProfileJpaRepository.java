package io.multiagent.expense.settings.repository;

import io.multiagent.expense.settings.entity.ConsultantProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConsultantProfileJpaRepository extends JpaRepository<ConsultantProfileEntity, UUID> {

    List<ConsultantProfileEntity> findByTenantId(UUID tenantId);

    List<ConsultantProfileEntity> findByTenantIdAndActiveTrue(UUID tenantId);

    Optional<ConsultantProfileEntity> findByTenantIdAndEmailIgnoreCase(UUID tenantId, String email);

    void deleteByTenantIdAndEmailIgnoreCase(UUID tenantId, String email);
}
