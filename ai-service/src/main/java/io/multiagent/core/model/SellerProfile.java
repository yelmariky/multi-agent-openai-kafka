package io.multiagent.core.model;

public record SellerProfile(
        String companyName,
        String address,
        String rcs,
        String iban,
        String bic,
        String email,
        String capital,
        String latePaymentClause,
        /** N° de TVA intracommunautaire (mention obligatoire sur facture B2B). */
        String vatNumber
) {
}
