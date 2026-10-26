package io.multiagent.core.policy.repository;

import io.multiagent.core.policy.entity.PolicyDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyDocumentJpaRepository extends JpaRepository<PolicyDocumentEntity, UUID> {

    Optional<PolicyDocumentEntity> findByTenantIdAndPolicyTypeAndStatus(UUID tenantId, String policyType, String status);

    List<PolicyDocumentEntity> findByTenantIdAndPolicyTypeOrderByVersionDesc(UUID tenantId, String policyType);

    Optional<PolicyDocumentEntity> findTopByTenantIdAndPolicyTypeOrderByVersionDesc(UUID tenantId, String policyType);

    Optional<PolicyDocumentEntity> findByIdAndTenantId(UUID id, UUID tenantId);
}
