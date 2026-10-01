package io.multiagent.expense.controller;

import io.multiagent.expense.entity.ExpenseEntity;
import io.multiagent.expense.infrastructure.kafka.EventPublisher;
import io.multiagent.expense.infrastructure.kafka.NotificationPayload;
import io.multiagent.expense.service.ExpenseDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
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

    /** Sert le justificatif (photo/PDF) associé à une dépense — réservé admin/manager (voir SecurityConfig). */
    @GetMapping("/{id}/receipt")
    public ResponseEntity<Resource> receipt(@PathVariable String id) {
        ExpenseEntity expense;
        try {
            expense = expenseDataService.findByIdForCurrentTenant(id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        if (expense == null || expense.getReceiptPath() == null || expense.getReceiptPath().isBlank()) {
            return ResponseEntity.notFound().build();
        }
        Path path = Path.of(expense.getReceiptPath());
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(contentTypeFor(path))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(path.getFileName().toString()).build().toString())
                .body(new FileSystemResource(path));
    }

    private MediaType contentTypeFor(Path path) {
        String ext = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (ext.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
        if (ext.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (ext.endsWith(".jpg") || ext.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        return MediaType.APPLICATION_OCTET_STREAM;
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
