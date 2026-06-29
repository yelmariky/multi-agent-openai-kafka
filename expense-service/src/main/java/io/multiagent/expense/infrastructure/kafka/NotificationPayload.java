package io.multiagent.expense.infrastructure.kafka;

/**
 * Payload sérialisé dans DomainEvent.payload pour le topic platform.notifications.
 *
 * target         : "ADMIN" | "CONSULTANT"
 * consultantEmail: email du consultant destinataire (obligatoire si target=CONSULTANT)
 * type           : type SSE (ex: EXPENSE_APPROVED, CRA_VALIDATED, LEAVE_APPROVED…)
 * message        : texte affiché dans la notification
 * refId          : identifiant de l'entité concernée (UUID ou id)
 */
public record NotificationPayload(
        String target,
        String consultantEmail,
        String type,
        String message,
        String refId
) {
    public static final String TARGET_ADMIN      = "ADMIN";
    public static final String TARGET_CONSULTANT = "CONSULTANT";
}
