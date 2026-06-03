package io.multiagent.core.model;

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
        List<ExpenseItem.AbsencePeriod> absencePeriods,
        /** Email du consultant — permet de filtrer les factures par consultant dans les rapports. */
        String consultantEmail
) {
}
