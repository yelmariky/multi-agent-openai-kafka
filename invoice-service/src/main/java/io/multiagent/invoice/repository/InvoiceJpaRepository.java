package io.multiagent.invoice.repository;

import io.multiagent.invoice.entity.InvoiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceJpaRepository extends JpaRepository<InvoiceEntity, UUID> {

    List<InvoiceEntity> findByTenantId(UUID tenantId);

    List<InvoiceEntity> findByTenantIdAndBillingMonthBetween(UUID tenantId, String start, String end);

    List<InvoiceEntity> findByTenantIdAndBillingMonthBetweenAndSellerCompanyNameIgnoreCase(
            UUID tenantId, String start, String end, String sellerCompanyName);

    List<InvoiceEntity> findByTenantIdAndBillingMonthAndSellerCompanyNameIgnoreCase(
            UUID tenantId, String billingMonth, String sellerCompanyName);

    Optional<InvoiceEntity> findByTenantIdAndInvoiceNameIgnoreCase(UUID tenantId, String invoiceName);

    List<InvoiceEntity> findByTenantIdAndInvoiceNameIgnoreCaseAndSellerCompanyNameIgnoreCase(
            UUID tenantId, String invoiceName, String sellerCompanyName);

    List<InvoiceEntity> findByTenantIdAndConsultantEmailIgnoreCase(UUID tenantId, String consultantEmail);
}
