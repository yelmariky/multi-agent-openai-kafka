package io.multiagent.core.reasoning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.multiagent.core.model.IntentResult;
import io.multiagent.core.model.ReasoningResult;
import io.multiagent.core.expense.service.RAGService;
import io.multiagent.core.expense.service.DeleteExpenseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReasoningService {

    // Une facture émise ne se supprime pas (numérotation continue, conservation légale) : elle s'annule
    // par un avoir depuis la console admin — plus de suppression en langage naturel.

    private final IntentClassifierService intentClassifierService;
    private final RAGService ragService;
    private final DeleteExpenseService deleteExpenseService;
    private final io.multiagent.core.i18n.Messages messages;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReasoningResult process(String userText) {
        return process(userText, null);
    }

    public ReasoningResult process(String userText, String consultantEmail) {
        return process(userText, consultantEmail, null);
    }

    public ReasoningResult process(String userText, String consultantEmail, String paymentModeOverride) {
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
            case "create_expense" -> ragService.extractSingleExpense(raw, intent, consultantEmail, paymentModeOverride);

            // 🟢 Pipeline génération d’un rapport
            case "generate_expense_report" -> ragService.extractExpenseReport(raw, intent);

            // 🔵 Pipeline génération de facture
            case "generate_invoice" -> ragService.extractInvoiceData(raw, intent);

            // 🔴 Suppression d’une note de frais par date
            case "delete_expense" -> deleteExpenseService.deleteByText(raw, intent);

            // 🔴 Suppression de facture désactivée : une facture émise s'annule par un avoir (console admin)
            case "delete_invoice" -> ReasoningResult.error(messages.get("reasoning.error.invoiceDeletionDisabled"));

            // 🟡 Smalltalk = réponse non métier
            case "smalltalk" -> ReasoningResult.smalltalk(messages.get("reasoning.smalltalk"));

            // 🔴 Erreur de classification LLM (API indisponible, quota, réponse invalide)
            case "error" -> ReasoningResult.error(messages.get("reasoning.error.classificationFailed"));

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
