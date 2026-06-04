package io.multiagent.notification.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Notification {
    private String id;
    private String type;
    private String consultantName;
    private String consultantEmail;
    private String message;
    private String timestamp;
    private boolean read;
    private String refId;
}
