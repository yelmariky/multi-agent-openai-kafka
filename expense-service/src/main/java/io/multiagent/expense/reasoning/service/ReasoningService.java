package io.multiagent.expense.reasoning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.multiagent.expense.model.IntentResult;
import io.multiagent.expense.model.ReasoningResult;
import io.multiagent.expense.service.DeleteExpenseService;
import io.multiagent.expense.service.RAGService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Orchestre le pipeline de raisonnement pour les notes de frais uniquement.
 * Les opérations factures (generate_invoice, delete_invoice) restent dans invoice-service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReasoningService {

    private static final Set<String> EXPENSE_INTENTS = Set.of(
            "create_expense", "generate_expense_report", "smalltalk", "error", "unknown"
    );

    private final IntentClassifierService intentClassifierService;
    private final RAGService              ragService;
    private final DeleteExpenseService    deleteExpenseService;
    private final io.multiagent.expense.i18n.Messages messages;
    private final ObjectMapper            mapper = new ObjectMapper();

    public ReasoningResult process(String userText, String consultantEmail) {
        return process(userText, consultantEmail, null);
    }

    public ReasoningResult process(String userText, String consultantEmail, String paymentModeOverride) {
        if (userText == null || userText.isBlank()) {
            return ReasoningResult.error(messages.get("reasoning.error.emptyText"));
        }

        IntentResult intent = tryParseIntent(userText);
        String raw = intent != null && intent.getOriginalText() != null ? intent.getOriginalText() : userText;
        if (intent == null) {
            intent = intentClassifierService.classify(userText);
            raw = intent.getOriginalText() != null ? intent.getOriginalText() : userText;
        }

        if ("error".equals(intent.getIntent())) {
            return ReasoningResult.builder()
                    .type("error").status("error").confidence(0.0)
                    .raw(messages.get("reasoning.error.classificationFailed"))
                    .metadata(java.util.Map.of("explanation", "Business error", "status", "ERROR"))
                    .build();
        }

        log.info("🎯 Intent détecté: {} (confidence={})", intent.getIntent(), intent.getConfidence());

        return switch (intent.getIntent()) {
            case "create_expense"         -> ragService.extractSingleExpense(raw, intent, consultantEmail, paymentModeOverride);
            case "generate_expense_report"-> ragService.extractExpenseReport(raw, intent);
            case "delete_expense"         -> deleteExpenseService.deleteByText(raw, intent);
            default -> ReasoningResult.builder()
                    .type("unknown").status("unknown").confidence(intent.getConfidence()).raw(raw)
                    .build();
        };
    }

    private IntentResult tryParseIntent(String payload) {
        try {
            JsonNode node = mapper.readTree(payload);
            if (node.has("intent")) {
                IntentResult r = new IntentResult();
                r.setIntent(node.path("intent").asText("unknown"));
                r.setConfidence(node.path("confidence").asDouble(1.0));
                r.setOriginalText(node.path("text").asText(payload));
                if (node.has("entities"))
                    r.setEntities(mapper.convertValue(node.get("entities"), java.util.Map.class));
                return r;
            }
        } catch (Exception ignored) {}
        return null;
    }
}
