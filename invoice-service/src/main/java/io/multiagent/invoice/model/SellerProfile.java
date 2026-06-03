package io.multiagent.invoice.model;

public record SellerProfile(
        String companyName,
        String address,
        String rcs,
        String iban,
        String bic,
        String email,
        String capital,
        String latePaymentClause
) {
}
