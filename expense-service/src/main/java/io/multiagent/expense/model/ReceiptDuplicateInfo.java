package io.multiagent.expense.model;

public record ReceiptDuplicateInfo(
        String binaryOf,
        String textOf
) {
    public boolean isBinaryDuplicate() {
        return binaryOf != null;
    }
    public boolean isTextDuplicate() {
        return textOf != null;
    }
}
