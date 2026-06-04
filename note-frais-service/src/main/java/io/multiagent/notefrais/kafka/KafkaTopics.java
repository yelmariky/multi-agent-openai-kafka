package io.multiagent.notefrais.kafka;

/**
 * Constantes des noms de topics Kafka cross-domaines.
 */
public final class KafkaTopics {

    private KafkaTopics() {
        // Classe utilitaire — pas d'instanciation
    }

    // --- Domaine : Notes de frais ---
    public static final String EXPENSE_CREATED         = "expense.created";
    public static final String EXPENSE_APPROVED        = "expense.approved";
    public static final String EXPENSE_ABSENCE_UPDATED = "expense.absence.updated";

    // --- Domaine : CRA ---
    public static final String CRA_SUBMITTED = "cra.submitted";
    public static final String CRA_VALIDATED = "cra.validated";
    public static final String CRA_REFUSED   = "cra.refused";

    // --- Domaine : Factures ---
    public static final String INVOICE_GENERATED = "invoice.generated";

    // --- Domaine : Reasoning / Intent routing ---
    public static final String REASONING_INTENT_ROUTED = "reasoning.intent.routed";
}
