package io.multiagent.notefrais.reasoning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.multiagent.notefrais.expense.service.DeleteExpenseService;
import io.multiagent.notefrais.expense.service.RAGService;
import io.multiagent.notefrais.model.IntentResult;
import io.multiagent.notefrais.model.ReasoningResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Service de reasoning local pour note-frais-service.
 * Dispatche sur les pipelines expense selon l'intent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotefraisReasoningService {

    private final RAGService ragService;
    private final DeleteExpenseService deleteExpenseService;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Process a text + optional consultantEmail without a pre-parsed intent.
     * Used by MonthlyLocationScheduler.
     */
    public ReasoningResult process(String userText, String consultantEmail) {
        IntentResult intent = IntentResult.builder()
                .intent("create_expense")
                .confidence(1.0)
                .originalText(userText)
                .build();
        return ragService.extractSingleExpense(userText, intent, consultantEmail);
    }

    /**
     * Processes the request with an already-parsed IntentResult.
     * Used by NotefraisReasoningController (called by the ai-core coordinator).
     */
    public ReasoningResult processWithIntent(String text, IntentResult intent, String consultantEmail) {
        log.info("NotefraisReasoningService.processWithIntent → intent={}", intent.getIntent());

        return switch (intent.getIntent()) {
            case "create_expense" -> ragService.extractSingleExpense(text, intent, consultantEmail);
            case "generate_expense_report" -> ragService.extractExpenseReport(text, intent);
            case "delete_expense" -> deleteExpenseService.deleteByText(text, intent);
            default -> ReasoningResult.builder()
                    .type("unknown")
                    .confidence(intent.getConfidence())
                    .raw(text)
                    .metadata(null)
                    .expenses(null)
                    .build();
        };
    }

    /**
     * Deserializes intentJson string and delegates to processWithIntent.
     */
    public ReasoningResult processFromJson(String text, String intentJson, String consultantEmail) {
        try {
            IntentResult intent;
            if (intentJson != null && !intentJson.isBlank()) {
                JsonNode node = mapper.readTree(intentJson);
                intent = IntentResult.builder()
                        .intent(node.path("intent").asText("create_expense"))
                        .confidence(node.path("confidence").asDouble(1.0))
                        .explanation(node.path("explanation").asText())
                        .originalText(node.has("originalText") ? node.get("originalText").asText() : text)
                        .entities(node.has("entities")
                                ? mapper.convertValue(node.get("entities"), Map.class)
                                : null)
                        .build();
            } else {
                intent = IntentResult.builder()
                        .intent("create_expense")
                        .confidence(1.0)
                        .originalText(text)
                        .build();
            }
            return processWithIntent(
                    intent.getOriginalText() != null ? intent.getOriginalText() : text,
                    intent,
                    consultantEmail);
        } catch (Exception e) {
            log.error("processFromJson error: {}", e.getMessage(), e);
            return ReasoningResult.error("Erreur de traitement : " + e.getMessage());
        }
    }
}
