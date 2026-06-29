package io.multiagent.expense.controller;

import io.multiagent.expense.entity.ExpenseEntity;
import io.multiagent.expense.infrastructure.kafka.EventPublisher;
import io.multiagent.expense.infrastructure.kafka.NotificationPayload;
import io.multiagent.expense.service.ExpenseDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/expenses")
@RequiredArgsConstructor
@Slf4j
public class ExpenseApprovalController {

    private final ExpenseDataService expenseDataService;
    private final EventPublisher  eventPublisher;

    @PostMapping("/approve")
    public ResponseEntity<Object> approve(@RequestBody Map<String, String> body) {
        try {
            String weaviateId = body.get("weaviateId");
            String note = body.get("note");
            if (weaviateId == null || weaviateId.isBlank())
                return ResponseEntity.badRequest().body("Missing 'weaviateId'");
            ExpenseEntity expense = expenseDataService.updateExpenseApproval(weaviateId, "APPROVED", note);
            eventPublisher.notify(NotificationPayload.TARGET_CONSULTANT,
                    expense.getConsultantEmail(), "EXPENSE_APPROVED",
                    "Votre note de frais" + expenseLabel(expense) + " a été approuvée.",
                    expense.getId().toString());
            return ResponseEntity.ok(Map.of("status", "APPROVED", "weaviateId", weaviateId));
        } catch (Exception e) {
            log.error("approve error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @PostMapping("/refuse")
    public ResponseEntity<Object> refuse(@RequestBody Map<String, String> body) {
        try {
            String weaviateId = body.get("weaviateId");
            String note = body.get("note");
            if (weaviateId == null || weaviateId.isBlank())
                return ResponseEntity.badRequest().body("Missing 'weaviateId'");
            ExpenseEntity expense = expenseDataService.updateExpenseApproval(weaviateId, "REFUSED", note);
            String msg = "Votre note de frais" + expenseLabel(expense) + " a été refusée"
                    + (note != null && !note.isBlank() ? " : " + note : ".");
            eventPublisher.notify(NotificationPayload.TARGET_CONSULTANT,
                    expense.getConsultantEmail(), "EXPENSE_REFUSED", msg, expense.getId().toString());
            return ResponseEntity.ok(Map.of("status", "REFUSED", "weaviateId", weaviateId));
        } catch (Exception e) {
            log.error("refuse error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    private String expenseLabel(ExpenseEntity e) {
        StringBuilder sb = new StringBuilder();
        if (e.getExpenseDate() != null) sb.append(" du ").append(e.getExpenseDate());
        if (e.getType() != null && !e.getType().isBlank()) sb.append(" (").append(e.getType());
        if (e.getAmount() != null) {
            sb.append(e.getType() != null && !e.getType().isBlank() ? " - " : " (");
            sb.append(String.format("%.2f", e.getAmount())).append(" ").append(e.getCurrency() != null ? e.getCurrency() : "EUR");
            sb.append(")");
        } else if (e.getType() != null && !e.getType().isBlank()) {
            sb.append(")");
        }
        return sb.toString();
    }
}
