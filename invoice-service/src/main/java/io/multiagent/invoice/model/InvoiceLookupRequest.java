package io.multiagent.invoice.model;

public record InvoiceLookupRequest(
        String billingMonth,
        String sellerCompanyName,
        String invoiceName
) {
}
