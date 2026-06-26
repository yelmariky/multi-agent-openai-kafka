package io.multiagent.invoice.service;

import io.multiagent.invoice.entity.InvoiceEntity;
import io.multiagent.invoice.infrastructure.tenant.TenantContext;
import io.multiagent.invoice.repository.InvoiceJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvoicePaymentService {

    private final InvoiceJpaRepository invoiceRepo;

    /** Marque la facture comme ENVOYÉE au client. */
    @Transactional
    public Map<String, Object> markSent(UUID id) {
        UUID tenantId = TenantContext.getTenantId();
        InvoiceEntity inv = findSecure(id, tenantId);
        inv.setSentDate(LocalDate.now());
        inv.setPaymentStatus("ENVOYEE");
        invoiceRepo.save(inv);
        log.info("Invoice {} marked as ENVOYEE", id);
        return toMap(inv);
    }

    /** Marque la facture comme PAYÉE avec la date de réception optionnelle. */
    @Transactional
    public Map<String, Object> markPaid(UUID id, String paymentDateStr) {
        UUID tenantId = TenantContext.getTenantId();
        InvoiceEntity inv = findSecure(id, tenantId);
        LocalDate payDate = parseDate(paymentDateStr);
        inv.setPaymentReceivedDate(payDate != null ? payDate : LocalDate.now());
        inv.setPaymentStatus("PAYEE");
        invoiceRepo.save(inv);
        log.info("Invoice {} marked as PAYEE on {}", id, inv.getPaymentReceivedDate());
        return toMap(inv);
    }

    /** Liste les factures par statut de paiement (null = toutes). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status) {
        UUID tenantId = TenantContext.getTenantId();
        List<InvoiceEntity> all = invoiceRepo.findByTenantId(tenantId);

        // Calculer EN_RETARD dynamiquement
        LocalDate today = LocalDate.now();
        all.forEach(inv -> {
            if (!"PAYEE".equals(inv.getPaymentStatus())
                    && inv.getPaymentDueDate() != null
                    && inv.getPaymentDueDate().isBefore(today)) {
                inv.setPaymentStatus("EN_RETARD");
            }
        });

        return all.stream()
                .filter(inv -> status == null || status.isBlank() || status.equalsIgnoreCase(inv.getPaymentStatus()))
                .map(this::toMap)
                .toList();
    }

    // -------------------------------------------------------------------------

    private InvoiceEntity findSecure(UUID id, UUID tenantId) {
        return invoiceRepo.findById(id)
                .filter(inv -> tenantId.equals(inv.getTenantId()))
                .orElseThrow(() -> new IllegalArgumentException("Facture introuvable : " + id));
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s); } catch (Exception ignored) { return null; }
    }

    private Map<String, Object> toMap(InvoiceEntity inv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                  inv.getId());
        m.put("invoiceName",         inv.getInvoiceName());
        m.put("invoiceNumber",       inv.getInvoiceNumber());
        m.put("billingMonth",        inv.getBillingMonth());
        m.put("clientCompanyName",   inv.getClientCompanyName());
        m.put("consultantEmail",     inv.getConsultantEmail());
        m.put("totalTtc",            inv.getTotalTtc());
        m.put("paymentDueDate",      inv.getPaymentDueDate());
        m.put("paymentStatus",       inv.getPaymentStatus() != null ? inv.getPaymentStatus() : "EN_ATTENTE");
        m.put("paymentReceivedDate", inv.getPaymentReceivedDate());
        m.put("sentDate",            inv.getSentDate());
        m.put("invoiceDate",         inv.getInvoiceDate());
        return m;
    }
}
