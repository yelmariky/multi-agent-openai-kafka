package io.multiagent.notefrais.model;

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
