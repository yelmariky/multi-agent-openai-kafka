package io.multiagent.core.infrastructure.kafka;

/**
 * Constantes des noms de topics Kafka cross-domaines.
 * Toujours utiliser ces constantes — ne jamais hardcoder les noms de topics ailleurs.
 * Les topics correspondants sont créés par le Job K8s kafka-topic-init (deploy/kafka/topics-events.yaml).
 */
public final class KafkaTopics {

    private KafkaTopics() {
        // Classe utilitaire — pas d'instanciation
    }

    // --- Domaine : Notes de frais ---
    public static final String EXPENSE_CREATED         = "expense.created";
    public static final String EXPENSE_APPROVED        = "expense.approved";
    public static final String EXPENSE_ABSENCE_UPDATED = "expense.absence.updated";

    // --- Domaine : CRA (Compte Rendu d'Activité) ---
    public static final String CRA_SUBMITTED = "cra.submitted";
    public static final String CRA_VALIDATED = "cra.validated";
    public static final String CRA_REFUSED   = "cra.refused";

    // --- Domaine : Factures ---
    public static final String INVOICE_GENERATED = "invoice.generated";

    // --- Domaine : Reasoning / Intent routing ---
    public static final String REASONING_INTENT_ROUTED = "reasoning.intent.routed";
}
