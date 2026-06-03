package io.multiagent.invoice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.multiagent.invoice.client.LLMAIClient;
import io.multiagent.invoice.model.InvoiceLookupRequest;
import io.multiagent.invoice.repository.InvoiceWeaviateRepository;
import io.multiagent.invoice.util.LLMUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class DeleteInvoiceService {

    private static final String DEFAULT_PROMPT = """
            Tu es un assistant qui extrait les paramètres nécessaires pour supprimer une facture.
            Tu réponds UNIQUEMENT en JSON strict, sans texte autour :
            {
              "invoiceName": "...",
              "billingMonth": "YYYY-MM",
              "sellerCompanyName": "..."
            }

            RÈGLES :
            - `billingMonth` : mois de facturation au format YYYY-MM. Si un mois est mentionné en toutes lettres (ex: "mars 2026", "le mois d'avril"), convertis-le en YYYY-MM.
            - `invoiceName` : numéro de facture (format F-YYYYMM-NN) si explicitement mentionné, sinon null.
            - `sellerCompanyName` : nom de la société émettrice de la facture. Si non mentionné, null.
            - Si une donnée est absente → null.
            """;

    private final InvoiceWeaviateRepository invoiceRepo;
    private final LLMAIClient llm;
    private final ObjectMapper objectMapper;

    @Value("${AI_CORE_PROMPT_DELETE_INVOICE:}")
    private String deleteInvoicePromptEnv;

    private String deleteInvoicePrompt;

    public DeleteInvoiceService(
            InvoiceWeaviateRepository invoiceRepo,
            LLMAIClient llm,
            ObjectMapper objectMapper) {
        this.invoiceRepo = invoiceRepo;
        this.llm = llm;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void loadPrompts() {
        deleteInvoicePrompt = (deleteInvoicePromptEnv != null && !deleteInvoicePromptEnv.isBlank())
                ? deleteInvoicePromptEnv
                : DEFAULT_PROMPT;
    }

    /**
     * Deletes an invoice by parsing natural language text.
     * Returns a Map with keys: deleted (int), invoiceName, billingMonth, sellerCompanyName, error (if any).
     */
    public Map<String, Object> deleteByText(String text) {
        InvoiceLookupRequest lookup = extractLookup(text);

        if (lookup == null || isBlank(lookup.sellerCompanyName())) {
            return errorMap("Suppression impossible : la société émettrice (sellerCompanyName) est requise.");
        }
        if (isBlank(lookup.billingMonth()) && isBlank(lookup.invoiceName())) {
            return errorMap("Suppression impossible : le mois de facturation ou le numéro de facture est requis.");
        }

        int deleted = invoiceRepo.deleteInvoices(lookup);

        Map<String, Object> result = new HashMap<>();
        result.put("invoiceName",       lookup.invoiceName()       != null ? lookup.invoiceName()       : "");
        result.put("billingMonth",      lookup.billingMonth()      != null ? lookup.billingMonth()      : "");
        result.put("sellerCompanyName", lookup.sellerCompanyName() != null ? lookup.sellerCompanyName() : "");
        result.put("deleted",           deleted);

        if (deleted <= 0) {
            result.put("error", "Aucune facture supprimée pour invoiceName=%s, billingMonth=%s, sellerCompanyName=%s"
                    .formatted(lookup.invoiceName(), lookup.billingMonth(), lookup.sellerCompanyName()));
        }
        return result;
    }

    private InvoiceLookupRequest extractLookup(String text) {
        try {
            var completion = llm.chatJson(null, deleteInvoicePrompt, text);
            String raw = LLMUtils.extractChatContent(completion);
            JsonNode node = objectMapper.readTree(raw);

            String invoiceName       = textOrNull(node, "invoiceName");
            String billingMonth      = textOrNull(node, "billingMonth");
            String sellerCompanyName = textOrNull(node, "sellerCompanyName");

            return new InvoiceLookupRequest(invoiceName, sellerCompanyName, billingMonth);
        } catch (Exception e) {
            log.error("DeleteInvoiceService extractLookup: {}", e.getMessage(), e);
            return null;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field)) return null;
        JsonNode v = node.get(field);
        if (v.isNull()) return null;
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static Map<String, Object> errorMap(String message) {
        Map<String, Object> map = new HashMap<>();
        map.put("deleted", 0);
        map.put("error", message);
        return map;
    }
}
