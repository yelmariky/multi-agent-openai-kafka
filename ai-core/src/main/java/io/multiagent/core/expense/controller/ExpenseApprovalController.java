package io.multiagent.core.expense.controller;

import io.multiagent.core.expense.entity.ExpenseEntity;
import io.multiagent.core.notification.service.ConsultantNotificationService;
import io.multiagent.core.service.WeaviateService;
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

    private final WeaviateService weaviateService;
    private final ConsultantNotificationService consultantNotificationService;

    @PostMapping("/approve")
    public ResponseEntity<Object> approve(@RequestBody Map<String, String> body) {
        try {
            String weaviateId = body.get("weaviateId");
            String note = body.get("note");
            if (weaviateId == null || weaviateId.isBlank()) {
                return ResponseEntity.badRequest().body("Missing 'weaviateId'");
            }
            ExpenseEntity expense = weaviateService.updateExpenseApproval(weaviateId, "APPROVED", note);
            notifyConsultant(expense, "EXPENSE_APPROVED",
                    "Votre note de frais" + expenseLabel(expense) + " a été approuvée.");
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
            if (weaviateId == null || weaviateId.isBlank()) {
                return ResponseEntity.badRequest().body("Missing 'weaviateId'");
            }
            ExpenseEntity expense = weaviateService.updateExpenseApproval(weaviateId, "REFUSED", note);
            String msg = "Votre note de frais" + expenseLabel(expense) + " a été refusée"
                    + (note != null && !note.isBlank() ? " : " + note : ".");
            notifyConsultant(expense, "EXPENSE_REFUSED", msg);
            return ResponseEntity.ok(Map.of("status", "REFUSED", "weaviateId", weaviateId));
        } catch (Exception e) {
            log.error("refuse error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    // -------------------------------------------------------------------------

    private void notifyConsultant(ExpenseEntity expense, String type, String message) {
        String email = expense.getConsultantEmail();
        if (email == null || email.isBlank()) return;
        try {
            consultantNotificationService.push(email, type, message, expense.getId().toString());
        } catch (Exception ex) {
            log.warn("Notification SSE échouée pour {} : {}", email, ex.getMessage());
        }
    }

    /** Construit un libellé court : " du 2026-06-10 (Restaurant - 28,00 EUR)" */
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
