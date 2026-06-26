package io.multiagent.invoice.repository;

import io.multiagent.invoice.entity.SellerProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SellerProfileJpaRepository extends JpaRepository<SellerProfileEntity, UUID> {

    Optional<SellerProfileEntity> findByTenantId(UUID tenantId);

    Optional<SellerProfileEntity> findByTenantIdAndCompanyNameIgnoreCase(UUID tenantId, String companyName);
}
