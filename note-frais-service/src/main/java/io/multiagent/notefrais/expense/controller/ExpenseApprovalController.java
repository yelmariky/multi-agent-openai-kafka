package io.multiagent.notefrais.expense.controller;

import io.multiagent.notefrais.expense.repository.ExpenseWeaviateRepository;
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

    private final ExpenseWeaviateRepository expenseRepo;

    @PostMapping("/approve")
    public ResponseEntity<Object> approve(@RequestBody Map<String, String> body) {
        try {
            String weaviateId = body.get("weaviateId");
            String note = body.get("note");
            if (weaviateId == null || weaviateId.isBlank()) {
                return ResponseEntity.badRequest().body("Missing 'weaviateId'");
            }
            expenseRepo.updateExpenseApproval(weaviateId, "APPROVED", note);
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
            expenseRepo.updateExpenseApproval(weaviateId, "REFUSED", note);
            return ResponseEntity.ok(Map.of("status", "REFUSED", "weaviateId", weaviateId));
        } catch (Exception e) {
            log.error("refuse error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }
}
