package io.multiagent.activity.infrastructure.kafka;

/**
 * Topics Kafka partagés entre tous les microservices.
 * Ne jamais hardcoder les noms de topics ailleurs.
 */
public final class KafkaTopics {
    private KafkaTopics() {}

    // ── Notes de frais ────────────────────────────────────────────────────────
    public static final String EXPENSE_CREATED         = "expense.created";
    public static final String EXPENSE_APPROVED        = "expense.approved";
    public static final String EXPENSE_REFUSED         = "expense.refused";
    public static final String EXPENSE_ABSENCE_UPDATED = "expense.absence.updated";

    // ── CRA (Compte Rendu d'Activité) ─────────────────────────────────────────
    public static final String CRA_SUBMITTED = "cra.submitted";
    public static final String CRA_VALIDATED = "cra.validated";
    public static final String CRA_REFUSED   = "cra.refused";

    // ── Congés ────────────────────────────────────────────────────────────────
    public static final String LEAVE_REQUESTED = "leave.requested";
    public static final String LEAVE_APPROVED  = "leave.approved";
    public static final String LEAVE_REFUSED   = "leave.refused";

    // ── Factures ─────────────────────────────────────────────────────────────
    public static final String INVOICE_GENERATED = "invoice.generated";

    // ── Notifications SSE (consommé par notification-service) ─────────────────
    public static final String NOTIFICATION = "platform.notifications";
}
