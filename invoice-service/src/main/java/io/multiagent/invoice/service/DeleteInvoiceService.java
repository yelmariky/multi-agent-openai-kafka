package io.multiagent.invoice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.multiagent.invoice.client.LLMAIClient;
import io.multiagent.invoice.entity.InvoiceEntity;
import io.multiagent.invoice.infrastructure.tenant.TenantContext;
import io.multiagent.invoice.model.InvoiceLookupRequest;
import io.multiagent.invoice.repository.InvoiceJpaRepository;
import io.multiagent.invoice.util.LLMUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class DeleteInvoiceService {

    private static final String DEFAULT_PROMPT = """
            Tu es un assistant qui extrait les parametres necessaires pour supprimer une facture.
            Tu reponds UNIQUEMENT en JSON strict, sans texte autour :
            {
              "invoiceName": "...",
              "billingMonth": "YYYY-MM",
              "sellerCompanyName": "..."
            }

            REGLES :
            - `billingMonth` : mois de facturation au format YYYY-MM. Si un mois est mentionne en toutes lettres (ex: "mars 2026", "le mois d'avril") convertis-le en YYYY-MM.
            - `invoiceName` : numero de facture (format F-YYYYMM-NN) si explicitement mentionne, sinon null.
            - `sellerCompanyName` : nom de la societe emettrice de la facture. Si non mentionne, null.
            - Si une donnee est absente -> null.
            """;

    private final InvoiceJpaRepository invoiceRepo;
    private final LLMAIClient llm;
    private final ObjectMapper objectMapper;

    @Value("${AI_CORE_PROMPT_DELETE_INVOICE:}")
    private String deleteInvoicePromptEnv;

    private String deleteInvoicePrompt;

    public DeleteInvoiceService(
            InvoiceJpaRepository invoiceRepo,
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

    @Transactional
    public Map<String, Object> deleteByText(String text) {
        InvoiceLookupRequest lookup = extractLookup(text);

        if (lookup == null || isBlank(lookup.sellerCompanyName())) {
            return errorMap("Suppression impossible : la societe emettrice (sellerCompanyName) est requise.");
        }
        if (isBlank(lookup.billingMonth()) && isBlank(lookup.invoiceName())) {
            return errorMap("Suppression impossible : le mois de facturation ou le numero de facture est requis.");
        }

        UUID tenantId = TenantContext.getTenantId();
        List<InvoiceEntity> toDelete = findInvoiceEntities(tenantId, lookup);
        int deleted = toDelete.size();
        if (!toDelete.isEmpty()) {
            invoiceRepo.deleteAll(toDelete);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("invoiceName",       lookup.invoiceName()       != null ? lookup.invoiceName()       : "");
        result.put("billingMonth",      lookup.billingMonth()      != null ? lookup.billingMonth()      : "");
        result.put("sellerCompanyName", lookup.sellerCompanyName() != null ? lookup.sellerCompanyName() : "");
        result.put("deleted",           deleted);

        if (deleted <= 0) {
            result.put("error", "Aucune facture supprimee pour invoiceName=%s, billingMonth=%s, sellerCompanyName=%s"
                    .formatted(lookup.invoiceName(), lookup.billingMonth(), lookup.sellerCompanyName()));
        }
        return result;
    }

    private List<InvoiceEntity> findInvoiceEntities(UUID tenantId, InvoiceLookupRequest request) {
        if (!isBlank(request.invoiceName()) && !isBlank(request.sellerCompanyName())) {
            return invoiceRepo.findByTenantIdAndInvoiceNameIgnoreCaseAndSellerCompanyNameIgnoreCase(
                    tenantId, request.invoiceName(), request.sellerCompanyName());
        }
        if (!isBlank(request.billingMonth()) && !isBlank(request.sellerCompanyName())) {
            return invoiceRepo.findByTenantIdAndBillingMonthAndSellerCompanyNameIgnoreCase(
                    tenantId, request.billingMonth(), request.sellerCompanyName());
        }
        return List.of();
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
