package io.multiagent.cra.kafka;

public final class KafkaTopics {
    private KafkaTopics() {}

    public static final String CRA_SUBMITTED = "cra.submitted";
    public static final String CRA_VALIDATED = "cra.validated";
    public static final String CRA_REFUSED   = "cra.refused";
}
