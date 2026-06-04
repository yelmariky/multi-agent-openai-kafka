package io.multiagent.notification.kafka;

public final class KafkaTopics {
    private KafkaTopics() {}
    public static final String EXPENSE_CREATED  = "expense.created";
    public static final String EXPENSE_APPROVED = "expense.approved";
    public static final String CRA_SUBMITTED    = "cra.submitted";
    public static final String CRA_VALIDATED    = "cra.validated";
    public static final String CRA_REFUSED      = "cra.refused";
}
