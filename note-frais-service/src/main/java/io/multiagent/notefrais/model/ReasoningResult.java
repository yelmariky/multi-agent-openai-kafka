package io.multiagent.notefrais.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ReasoningResult {

    /**
     * create_expense | generate_expense_report
     */
    private String type;

    private String status;

    /**
     * Niveau de confiance provenant du IntentClassifier
     */
    private double confidence;

    /**
     * Liste des dépenses (une ou plusieurs)
     */
    private List<ExpenseItem> expenses;

    /**
     * Texte original fourni par l'utilisateur
     */
    private String raw;

    /**
     * Métadonnées pour audit
     */
    private Map<String, Object> metadata;

    public static ReasoningResult smalltalk(String message) {
        return ReasoningResult.builder()
                .type("smalltalk")
                .confidence(0.2)
                .status("smalltalk")
                .raw(message)
                .expenses(List.of())
                .metadata(Map.of(
                        "explanation", "Smalltalk hors périmètre",
                        "status", "SMALLTALK"
                ))
                .build();
    }

    public static ReasoningResult error(String message) {
        return ReasoningResult.builder()
                .type("error")
                .confidence(0.0)
                .raw(message)
                .status("error")
                .expenses(List.of())
                .metadata(Map.of(
                        "explanation", "Erreur métier",
                        "status", "ERROR"
                ))
                .build();
    }

    public static ReasoningResult expenseResult(ExpenseItem expense, double confidence) {
        return ReasoningResult.builder()
                .type("create_expense")
                .confidence(confidence)
                .status("create expense_OK")
                .raw(expense.getOriginalText())
                .expenses(List.of(expense))
                .metadata(Map.of(
                        "explanation", "Pipeline create_expense",
                        "status", "EXPENSE_CREATED"
                ))
                .build();
    }

    public static ReasoningResult expenseReport(List<ExpenseItem> expenses, double confidence) {
        return ReasoningResult.builder()
                .type("generate_expense_report")
                .confidence(confidence)
                .status("expense_report_OK")
                .raw("expense_report")
                .expenses(expenses)
                .metadata(Map.of(
                        "count", expenses.size(),
                        "explanation", "Pipeline generate_expense_report",
                        "status", "EXPENSE_REPORT"
                ))
                .build();
    }
}
