package io.multiagent.invoice.service;

import io.multiagent.invoice.entity.InvoiceEntity;
import io.multiagent.invoice.infrastructure.tenant.TenantContext;
import io.multiagent.invoice.repository.InvoiceJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Annulation d'une facture — jamais de suppression physique (numérotation continue, conservation légale).
 * <ul>
 *   <li>EN_ATTENTE (jamais envoyée) → ANNULEE, ligne conservée, pas d'avoir ;</li>
 *   <li>ENVOYEE / EN_RETARD → ANNULEE + avoir A-AAAAMM-NN aux montants négatifs ;</li>
 *   <li>PAYEE, déjà ANNULEE, ou avoir → refus ({@link InvoiceService.InvoiceLockedException}).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceCancellationService {

    private final InvoiceJpaRepository invoiceRepo;
    private final InvoiceService invoiceService;

    @Transactional
    public Map<String, Object> cancel(UUID invoiceId) throws IOException {
        UUID tenantId = TenantContext.getTenantId();
        InvoiceEntity inv = invoiceRepo.findById(invoiceId)
                .filter(i -> tenantId.equals(i.getTenantId()))
                .orElseThrow(() -> new IllegalArgumentException("Facture introuvable : " + invoiceId));

        if (InvoiceService.TYPE_CREDIT_NOTE.equalsIgnoreCase(inv.getInvoiceType())) {
            throw new InvoiceService.InvoiceLockedException("Un avoir ne peut pas être annulé.");
        }
        String status = inv.getPaymentStatus() == null ? InvoiceService.STATUS_DRAFT : inv.getPaymentStatus();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("invoiceName", inv.getInvoiceName());

        switch (status) {
            case InvoiceService.STATUS_DRAFT -> {
                inv.setPaymentStatus(InvoiceService.STATUS_CANCELLED);
                invoiceRepo.save(inv);
                log.info("Facture {} annulée (brouillon, sans avoir)", inv.getInvoiceName());
            }
            case "ENVOYEE", "EN_RETARD" -> {
                inv.setPaymentStatus(InvoiceService.STATUS_CANCELLED);
                invoiceRepo.save(inv);
                InvoiceEntity credit = invoiceService.issueCreditNote(inv);
                result.put("creditNoteId", credit.getId());
                result.put("creditNoteName", credit.getInvoiceName());
                log.info("Facture {} annulée par l'avoir {}", inv.getInvoiceName(), credit.getInvoiceName());
            }
            case "PAYEE" -> throw new InvoiceService.InvoiceLockedException("La facture " + inv.getInvoiceName()
                    + " est payée : un remboursement éventuel se traite hors application.");
            default -> throw new InvoiceService.InvoiceLockedException(
                    "La facture " + inv.getInvoiceName() + " est déjà annulée.");
        }
        result.put("status", InvoiceService.STATUS_CANCELLED);
        return result;
    }
}
