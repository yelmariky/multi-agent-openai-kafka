package io.multiagent.core.expense.repository;

import io.multiagent.core.expense.entity.ExpenseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExpenseJpaRepository extends JpaRepository<ExpenseEntity, UUID> {

    List<ExpenseEntity> findByTenantIdAndExpenseDateBetween(UUID tenantId, LocalDate start, LocalDate end);

    List<ExpenseEntity> findByTenantIdAndConsultantEmailIgnoreCase(UUID tenantId, String consultantEmail);

    List<ExpenseEntity> findByTenantIdAndExpenseDateBetweenAndConsultantEmailIgnoreCase(
            UUID tenantId, LocalDate start, LocalDate end, String consultantEmail);

    List<ExpenseEntity> findByTenantIdAndCompanyIgnoreCase(UUID tenantId, String company);

    @Query("SELECT COALESCE(MAX(e.expenseId), 0) FROM ExpenseEntity e WHERE e.tenantId = :tenantId")
    int findMaxExpenseId(@Param("tenantId") UUID tenantId);

    Optional<ExpenseEntity> findByTenantIdAndHash(UUID tenantId, String hash);

    List<ExpenseEntity> findByTenantIdAndApprovalStatus(UUID tenantId, String approvalStatus);

    void deleteByTenantIdAndExpenseDateBetween(UUID tenantId, LocalDate start, LocalDate end);

    @Query(value = "SELECT * FROM expense WHERE tenant_id = :tenantId " +
            "ORDER BY embedding <=> CAST(:queryVector AS vector) LIMIT :limit",
            nativeQuery = true)
    List<ExpenseEntity> findSimilar(@Param("tenantId") UUID tenantId,
                                   @Param("queryVector") String queryVector,
                                   @Param("limit") int limit);
}
