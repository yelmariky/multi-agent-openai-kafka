package io.multiagent.notification.model;

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
