package io.multiagent.expense.model;

public record InvoiceLookupRequest(
        String billingMonth,
        String sellerCompanyName,
        String invoiceName
) {
}
