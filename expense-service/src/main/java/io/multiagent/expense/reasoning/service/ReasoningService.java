package io.multiagent.expense.reasoning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.multiagent.expense.model.IntentResult;
import io.multiagent.expense.model.ReasoningResult;
import io.multiagent.expense.expense.service.RAGService;
import io.multiagent.expense.expense.service.DeleteExpenseService;
import io.multiagent.expense.invoice.client.InvoiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReasoningService {

    private final IntentClassifierService intentClassifierService;
    private final RAGService ragService;
    private final DeleteExpenseService deleteExpenseService;
    private final InvoiceClient invoiceClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReasoningResult process(String userText) {
        return process(userText, null);
    }

    public ReasoningResult process(String userText, String consultantEmail) {
        IntentResult intent = tryParseIntent(userText);
        String raw;
        if (intent == null) {
            // Retirer l'injection [absences: ...] avant classification : c'est du bruit pour le LLM
            String textForClassification = userText.replaceAll("(?i)\\[absences:[^\\]]*\\]", "").trim();
            intent = intentClassifierService.classify(textForClassification);
            raw = userText; // Garder le texte complet (avec absences) pour le RAG
        } else {
            raw = intent.getOriginalText() != null ? intent.getOriginalText() : userText;
        }

        if ("error".equals(intent.getIntent())) {
            log.warn("⚠️ Classification LLM en erreur — texte renvoyé tel quel sans traitement");
        }

        log.info("🎯 Intent détecté: {} (confidence={})", intent.getIntent(), intent.getConfidence());

        return switch (intent.getIntent()) {

            // 🔵 Pipeline création d’une note de frais
            case "create_expense" -> ragService.extractSingleExpense(raw, intent, consultantEmail);

            // 🟢 Pipeline génération d’un rapport
            case "generate_expense_report" -> ragService.extractExpenseReport(raw, intent);

            // 🔵 Pipeline génération de facture
            case "generate_invoice" -> ragService.extractInvoiceData(raw, intent);

            // 🔴 Suppression d’une note de frais par date
            case "delete_expense" -> deleteExpenseService.deleteByText(raw, intent);

            // 🔴 Suppression d’une facture par mois/numéro — délégué à invoice-service
            case "delete_invoice" -> buildDeleteInvoiceResult(raw, intent);

            // 🟡 Smalltalk = réponse non métier
            case "smalltalk" -> ReasoningResult.smalltalk("Je suis un agent métier, pas un chatbot général.");

            // 🔴 Erreur de classification LLM (API indisponible, quota, réponse invalide)
            case "error" -> ReasoningResult.error("Classification échouée - veuillez reformuler votre demande ou réessayer.");

            // 🔴 Intent inconnu
            default -> ReasoningResult.builder()
                    .type("unknown")
                    .confidence(intent.getConfidence())
                    .raw(raw)
                    .metadata(null)
                    .expenses(null)
                    .build();
        };
    }

    private ReasoningResult buildDeleteInvoiceResult(String raw, IntentResult intent) {
        Map<String, Object> result = invoiceClient.deleteByText(raw);
        int deleted = result.get("deleted") instanceof Number n ? n.intValue() : 0;
        String error = result.get("error") instanceof String s ? s : null;

        if (error != null && deleted <= 0) {
            return ReasoningResult.error(error);
        }

        Map<String, Object> meta = new java.util.HashMap<>(result);
        meta.remove("error");
        return ReasoningResult.builder()
                .type("delete_invoice")
                .status("INVOICE_DELETED")
                .confidence(intent != null ? intent.getConfidence() : 1.0)
                .raw(raw)
                .metadata(meta)
                .expenses(null)
                .build();
    }

    @SuppressWarnings("unchecked")
    private IntentResult tryParseIntent(String text) {
        try {
            JsonNode node = mapper.readTree(text);
            if (node.has("intent")) {
                IntentResult r = new IntentResult();
                r.setIntent(node.path("intent").asText("unknown"));
                r.setConfidence(node.path("confidence").asDouble());
                r.setExplanation(node.path("explanation").asText());
                // Priorité à originalText si présent
                if (node.has("originalText")) {
                    r.setOriginalText(node.get("originalText").asText());
                } else if (node.has("text")) {
                    r.setOriginalText(node.get("text").asText());
                }
                if (node.has("entities")) {
                    r.setEntities(mapper.convertValue(node.get("entities"), Map.class));
                }
                return r;
            }
        } catch (Exception ignored) {
            // not a JSON intent envelope — treat as plain text
        }
        return null;
    }

}
