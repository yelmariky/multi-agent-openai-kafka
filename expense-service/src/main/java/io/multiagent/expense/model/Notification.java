package io.multiagent.expense.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Notification {
    private String id;
    private String type;        // CRA_SUBMITTED | EXPENSE_CREATED
    private String consultantName;
    private String consultantEmail;
    private String message;
    private String timestamp;
    private boolean read;
    private String refId;       // CRA uuid or expense id
}
