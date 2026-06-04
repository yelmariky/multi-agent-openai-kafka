package io.multiagent.core.reasoning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.multiagent.core.model.IntentResult;
import io.multiagent.core.model.ReasoningResult;
import io.multiagent.core.invoice.client.InvoiceClient;
import io.multiagent.core.notefrais.client.NotefraisClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Coordinator — classifies intent then delegates to the appropriate sub-agent via HTTP.
 *
 * Sub-agents:
 *   - note-frais-service  → expense creation, reports, deletion
 *   - invoice-service     → invoice deletion
 *
 * No business logic lives here; this service is routing-only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReasoningService {

    private final IntentClassifierService intentClassifierService;
    private final NotefraisClient notefraisClient;
    private final InvoiceClient invoiceClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReasoningResult process(String userText) {
        return process(userText, null);
    }

    public ReasoningResult process(String userText, String consultantEmail) {
        IntentResult intent = tryParseIntent(userText);
        String raw;
        if (intent == null) {
            intent = intentClassifierService.classify(userText);
            raw = userText;
        } else {
            raw = intent.getOriginalText() != null ? intent.getOriginalText() : userText;
        }
        log.info("🎯 Intent détecté: {} — routing to sub-agent", intent.getIntent());

        return switch (intent.getIntent()) {

            // 🔵 Notes de frais — délégué à note-frais-service
            case "create_expense"           -> notefraisClient.process(raw, intent, consultantEmail);
            case "generate_expense_report"  -> notefraisClient.process(raw, intent, consultantEmail);
            case "generate_invoice"         -> notefraisClient.process(raw, intent, consultantEmail);
            case "delete_expense"           -> notefraisClient.process(raw, intent, consultantEmail);

            // 🔴 Suppression facture — délégué à invoice-service
            case "delete_invoice"           -> buildDeleteInvoiceResult(raw, intent);

            // 🟡 Hors périmètre métier
            case "smalltalk"                -> ReasoningResult.smalltalk("Je suis un agent métier, pas un chatbot général.");

            // ⚫ Intent inconnu
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
