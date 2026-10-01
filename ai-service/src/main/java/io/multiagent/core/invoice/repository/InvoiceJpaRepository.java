package io.multiagent.core.invoice.repository;

import io.multiagent.core.invoice.entity.InvoiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Lecture seule — voir {@link InvoiceEntity}. */
public interface InvoiceJpaRepository extends JpaRepository<InvoiceEntity, UUID> {

    List<InvoiceEntity> findByTenantIdAndBillingMonthBetween(UUID tenantId, String startMonth, String endMonth);

    List<InvoiceEntity> findByTenantIdAndBillingMonthBetweenAndConsultantEmailIgnoreCase(
            UUID tenantId, String startMonth, String endMonth, String consultantEmail);

    List<InvoiceEntity> findByTenantIdAndBillingMonthBetweenAndClientCompanyNameIgnoreCase(
            UUID tenantId, String startMonth, String endMonth, String clientCompanyName);

    List<InvoiceEntity> findByTenantIdAndInvoiceNameIgnoreCase(UUID tenantId, String invoiceName);
}
