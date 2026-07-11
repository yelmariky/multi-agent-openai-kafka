package io.multiagent.invoice.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Demande de facture d'abonnement SaaS IA-INSIGHT.
 * La grille tarifaire (Essentiel 29€, Croissance 49€, -20% en annuel) est
 * appliquée par SubscriptionInvoiceService ; Enterprise exige un prix négocié.
 */
public record SubscriptionInvoiceRequest(
        String clientCompanyName,
        String clientAddress,
        /** SIREN/RCS du client — obligatoire sur une facture B2B française. */
        String clientRcs,
        /** ESSENTIEL | CROISSANCE | ENTERPRISE */
        String offer,
        Integer consultantCount,
        /** MENSUEL | TRIMESTRIEL | ANNUEL (annuel = -20%) */
        String billingPeriod,
        /** Premier jour de la période facturée (facturation d'avance). */
        LocalDate periodStart,
        /** Prix mensuel HT négocié par consultant — obligatoire pour ENTERPRISE, ignoré sinon. */
        BigDecimal negotiatedMonthlyPriceHt,
        /** Émetteur — défaut "IA-INSIGHT" (profil vendeur résolu en base : IBAN, capital, adresse). */
        String sellerCompanyName,
        LocalDate invoiceDate,
        String notes
) {
}
