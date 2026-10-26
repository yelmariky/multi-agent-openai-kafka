package io.multiagent.invoice.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record SimpleInvoiceRequest(
        String invoiceName,
        LocalDate invoiceDate,
        String billingMonth,
        String sellerCompanyName,
        String sellerAddress,
        String sellerRcs,
        String clientCompanyName,
        String clientAddress,
        String clientRcs,
        String invoiceTitle,
        Double daysCount,
        BigDecimal unitPriceHt,
        BigDecimal totalHt,
        BigDecimal vatRate,
        BigDecimal totalTtc,
        String currency,
        LocalDate paymentDueDate,
        String latePaymentClause,
        String notes,
        /** Périodes d'absence — renseignées par le LLM quand l'utilisateur mentionne des congés/absences.
         *  Si présentes, le backend recalcule daysCount = jours ouvrés du billingMonth - absences. */
        List<AbsencePeriod> absencePeriods,
        /** Email du consultant — permet de filtrer les factures par consultant dans les rapports. */
        String consultantEmail,
        /** Nom du projet assigné au consultant (optionnel, résolu depuis la DB si absent). */
        String projectName,
        /** FACTURE (défaut) | AVOIR — pilote le titre du document. */
        String invoiceType,
        /** N° de TVA intracommunautaire du vendeur (repli : profil vendeur). */
        String sellerVatNumber,
        /** N° de TVA intracommunautaire du client. */
        String clientVatNumber
) {
    /** Signature historique (sans type ni TVA) — conservée pour les appelants existants. */
    public SimpleInvoiceRequest(String invoiceName, LocalDate invoiceDate, String billingMonth,
            String sellerCompanyName, String sellerAddress, String sellerRcs,
            String clientCompanyName, String clientAddress, String clientRcs,
            String invoiceTitle, Double daysCount, BigDecimal unitPriceHt, BigDecimal totalHt,
            BigDecimal vatRate, BigDecimal totalTtc, String currency, LocalDate paymentDueDate,
            String latePaymentClause, String notes, List<AbsencePeriod> absencePeriods,
            String consultantEmail, String projectName) {
        this(invoiceName, invoiceDate, billingMonth, sellerCompanyName, sellerAddress, sellerRcs,
                clientCompanyName, clientAddress, clientRcs, invoiceTitle, daysCount, unitPriceHt, totalHt,
                vatRate, totalTtc, currency, paymentDueDate, latePaymentClause, notes, absencePeriods,
                consultantEmail, projectName, null, null, null);
    }

    public boolean isCreditNote() {
        return "AVOIR".equalsIgnoreCase(invoiceType);
    }
}
