package io.multiagent.expense.repository;

import io.multiagent.expense.entity.ExpenseEntity;
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

    @Query("SELECT COUNT(e) > 0 FROM ExpenseEntity e WHERE e.tenantId = :tenantId " +
           "AND LOWER(e.consultantEmail) = LOWER(:email) " +
           "AND LOWER(e.type) = LOWER(:type) " +
           "AND e.expenseDate BETWEEN :start AND :end " +
           "AND (e.approvalStatus IS NULL OR e.approvalStatus <> 'REFUSED')")
    boolean existsByMonthAndType(@Param("tenantId") UUID tenantId,
                                 @Param("email") String email,
                                 @Param("type") String type,
                                 @Param("start") LocalDate start,
                                 @Param("end") LocalDate end);

    @Query(value = "SELECT * FROM expense WHERE tenant_id = :tenantId " +
            "ORDER BY embedding <=> CAST(:queryVector AS vector) LIMIT :limit",
            nativeQuery = true)
    List<ExpenseEntity> findSimilar(@Param("tenantId") UUID tenantId,
                                   @Param("queryVector") String queryVector,
                                   @Param("limit") int limit);
}
