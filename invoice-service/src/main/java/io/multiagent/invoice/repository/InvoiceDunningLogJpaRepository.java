package io.multiagent.invoice.repository;

import io.multiagent.invoice.entity.InvoiceDunningLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceDunningLogJpaRepository extends JpaRepository<InvoiceDunningLogEntity, UUID> {

    List<InvoiceDunningLogEntity> findByInvoiceIdOrderByStageAsc(UUID invoiceId);

    boolean existsByInvoiceIdAndStage(UUID invoiceId, int stage);

    Optional<InvoiceDunningLogEntity> findFirstByInvoiceIdOrderByStageDesc(UUID invoiceId);
}
