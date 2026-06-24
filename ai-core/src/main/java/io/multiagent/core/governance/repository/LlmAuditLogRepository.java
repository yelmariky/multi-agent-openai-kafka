package io.multiagent.core.governance.repository;

import io.multiagent.core.governance.entity.LlmAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Repository
public interface LlmAuditLogRepository extends JpaRepository<LlmAuditLog, UUID> {

    @Query("""
        SELECT COALESCE(SUM(l.totalTokens), 0)
        FROM LlmAuditLog l
        WHERE l.tenantId = :tenantId
          AND l.createdAt >= :since
        """)
    long sumTokensSince(@Param("tenantId") UUID tenantId, @Param("since") Instant since);

    @Query("""
        SELECT COUNT(l)
        FROM LlmAuditLog l
        WHERE l.tenantId = :tenantId
          AND l.feature = :feature
          AND l.createdAt >= :since
        """)
    long countByFeatureSince(@Param("tenantId") UUID tenantId,
                             @Param("feature") String feature,
                             @Param("since") Instant since);
}
