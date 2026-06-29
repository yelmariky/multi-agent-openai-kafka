package io.multiagent.core.dashboard.repository;

import io.multiagent.core.dashboard.entity.InvoiceSummaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface InvoiceDashboardRepository extends JpaRepository<InvoiceSummaryEntity, UUID> {

    /** Factures en retard : échéance dépassée et non payées. */
    @Query("SELECT i FROM InvoiceSummaryEntity i WHERE i.tenantId = :tid AND i.paymentDueDate < :today AND (i.paymentStatus IS NULL OR i.paymentStatus != 'PAYEE')")
    List<InvoiceSummaryEntity> findOverdue(@Param("tid") UUID tenantId, @Param("today") LocalDate today);

    List<InvoiceSummaryEntity> findByTenantId(UUID tenantId);
}
