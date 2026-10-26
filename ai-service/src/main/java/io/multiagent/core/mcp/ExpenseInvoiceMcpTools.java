package io.multiagent.core.mcp;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.multiagent.core.infrastructure.tenant.TenantContext;
import io.multiagent.core.invoice.entity.InvoiceEntity;
import io.multiagent.core.invoice.repository.InvoiceJpaRepository;
import io.multiagent.core.model.ExpenseItem;
import io.multiagent.core.service.WeaviateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Outils MCP exposés en lecture seule aux systèmes externes (voir McpApiKeyFilter pour l'auth et
 * le tenant-scoping). Ne réimplémente aucune logique métier — délègue toujours aux services/
 * repositories déjà utilisés par les consoles admin/consultant, pour rester la seule source de
 * vérité. Aucune méthode ici ne doit jamais muter des données (v1 = consultation uniquement).
 *
 * <p>Chaque outil accepte un paramètre {@link ToolContext} (injecté automatiquement par Spring AI,
 * invisible pour le client MCP) utilisé uniquement comme filet de sécurité : les transports SSE et
 * Streamable HTTP exécutent le corps des outils sur un thread Reactor distinct de celui qui a
 * authentifié la requête HTTP, donc {@link TenantContext} (ThreadLocal) y est vide. Voir
 * {@link #withTenant}.</p>
 */
@Slf4j
@Component
public class ExpenseInvoiceMcpTools {

    private final WeaviateService weaviateService;
    private final InvoiceJpaRepository invoiceRepository;
    private final McpSessionTenantRegistry sessionTenantRegistry;
    private final io.multiagent.core.i18n.Messages messages;

    public ExpenseInvoiceMcpTools(WeaviateService weaviateService, InvoiceJpaRepository invoiceRepository,
            McpSessionTenantRegistry sessionTenantRegistry, io.multiagent.core.i18n.Messages messages) {
        this.messages = messages;
        this.weaviateService = weaviateService;
        this.invoiceRepository = invoiceRepository;
        this.sessionTenantRegistry = sessionTenantRegistry;
    }

    @Tool(description = "Liste les notes de frais d'un tenant sur une période, avec filtres optionnels "
            + "par email consultant, type de dépense et statut d'approbation (PENDING/APPROVED/REFUSED).")
    public List<Map<String, Object>> list_expenses(
            @ToolParam(description = "Date de début, format YYYY-MM-DD") String startDate,
            @ToolParam(description = "Date de fin, format YYYY-MM-DD") String endDate,
            @ToolParam(description = "Email du consultant (optionnel)", required = false) String consultantEmail,
            @ToolParam(description = "Type de dépense (optionnel, ex: restaurant, hôtel, frais_km)", required = false) String type,
            @ToolParam(description = "Statut d'approbation (optionnel: PENDING, APPROVED, REFUSED)", required = false) String approvalStatus,
            ToolContext toolContext) {
        return withTenant("list_expenses", toolContext, tenantId -> {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);
            List<ExpenseItem> expenses = weaviateService.findExpensesBetween(start, end, type, null, consultantEmail);
            return expenses.stream()
                    .filter(e -> isBlank(approvalStatus) || approvalStatus.equalsIgnoreCase(e.getApprovalStatus()))
                    .map(this::toMap)
                    .toList();
        });
    }

    @Tool(description = "Résumé agrégé des notes de frais d'un mois : nombre total, totaux Personnel/Business, "
            + "et répartition par statut d'approbation.")
    public Map<String, Object> get_expense_summary(
            @ToolParam(description = "Mois au format YYYY-MM") String month,
            ToolContext toolContext) {
        return withTenant("get_expense_summary", toolContext, tenantId -> {
            java.time.YearMonth ym = java.time.YearMonth.parse(month);
            List<ExpenseItem> expenses = weaviateService.findExpensesBetween(ym.atDay(1), ym.atEndOfMonth());

            double totalPersonnel = expenses.stream()
                    .filter(e -> containsIgnoreCase(e.getPaymentMode(), "personnel"))
                    .mapToDouble(e -> e.getAmount() != null ? e.getAmount() : 0.0).sum();
            double totalBusiness = expenses.stream()
                    .filter(e -> containsIgnoreCase(e.getPaymentMode(), "business"))
                    .mapToDouble(e -> e.getAmount() != null ? e.getAmount() : 0.0).sum();
            long approved = expenses.stream().filter(e -> "APPROVED".equalsIgnoreCase(e.getApprovalStatus())).count();
            long refused = expenses.stream().filter(e -> "REFUSED".equalsIgnoreCase(e.getApprovalStatus())).count();
            long pending = expenses.size() - approved - refused;

            return Map.of(
                    "month", month,
                    "count", expenses.size(),
                    "totalPersonnel", round2(totalPersonnel),
                    "totalBusiness", round2(totalBusiness),
                    "approved", approved,
                    "pending", pending,
                    "refused", refused
            );
        });
    }

    @Tool(description = "Liste les factures d'un tenant sur une plage de mois, avec filtres optionnels "
            + "par nom de client et email consultant.")
    public List<Map<String, Object>> list_invoices(
            @ToolParam(description = "Mois de début, format YYYY-MM") String startMonth,
            @ToolParam(description = "Mois de fin, format YYYY-MM") String endMonth,
            @ToolParam(description = "Nom du client (optionnel)", required = false) String clientCompanyName,
            @ToolParam(description = "Email du consultant (optionnel)", required = false) String consultantEmail,
            ToolContext toolContext) {
        return withTenant("list_invoices", toolContext, tenantId -> {
            List<InvoiceEntity> invoices;
            if (!isBlank(clientCompanyName)) {
                invoices = invoiceRepository.findByTenantIdAndBillingMonthBetweenAndClientCompanyNameIgnoreCase(
                        tenantId, startMonth, endMonth, clientCompanyName);
            } else if (!isBlank(consultantEmail)) {
                invoices = invoiceRepository.findByTenantIdAndBillingMonthBetweenAndConsultantEmailIgnoreCase(
                        tenantId, startMonth, endMonth, consultantEmail);
            } else {
                invoices = invoiceRepository.findByTenantIdAndBillingMonthBetween(tenantId, startMonth, endMonth);
            }
            return invoices.stream().map(this::toMap).toList();
        });
    }

    @Tool(description = "Détail et statut de paiement d'une facture précise par son nom (ex: F-202610-01).")
    public Map<String, Object> get_invoice_status(
            @ToolParam(description = "Nom exact de la facture, ex: F-202610-01") String invoiceName,
            ToolContext toolContext) {
        return withTenant("get_invoice_status", toolContext, tenantId ->
                invoiceRepository.findByTenantIdAndInvoiceNameIgnoreCase(tenantId, invoiceName).stream()
                        .findFirst()
                        .map(this::toMap)
                        .orElse(Map.of("error", messages.get("mcp.error.invoiceNotFound", invoiceName))));
    }

    /**
     * Pose {@link TenantContext} pour la durée de l'appel puis le nettoie systématiquement —
     * indispensable : les threads {@code boundedElastic} sont réutilisés par Reactor, donc un
     * contexte non nettoyé pourrait fuiter vers l'appel (potentiellement d'un autre tenant) suivant
     * sur ce même thread.
     */
    private <T> T withTenant(String tool, ToolContext toolContext, Function<UUID, T> action) {
        UUID tenantId = TenantContext.getTenantIdOrNull();
        if (tenantId == null) {
            String sessionId = extractSessionId(toolContext);
            tenantId = sessionTenantRegistry.get(sessionId);
            if (tenantId == null) {
                throw new IllegalStateException(messages.get("mcp.error.tenantMissing"));
            }
            TenantContext.set(tenantId, null);
        }
        log.info("🔌 [MCP] tool={} tenant={}", tool, tenantId);
        try {
            return action.apply(tenantId);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Récupère l'id de session MCP — identique à celui que {@link McpApiKeyFilter} résout côté
     * requête HTTP (sessionId SSE traduit, ou header {@code Mcp-Session-Id} pour Streamable HTTP) et
     * utilise pour peupler {@link McpSessionTenantRegistry}.
     */
    private String extractSessionId(ToolContext toolContext) {
        if (toolContext == null) return null;
        return McpToolUtils.getMcpExchange(toolContext).map(McpSyncServerExchange::sessionId).orElse(null);
    }

    private Map<String, Object> toMap(ExpenseItem e) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("date", e.getDate());
        m.put("type", e.getType());
        m.put("amount", e.getAmount());
        m.put("currency", e.getCurrency());
        m.put("description", e.getDescription());
        m.put("company", e.getCompany());
        m.put("address", e.getAddress());
        m.put("consultantEmail", e.getConsultantEmail());
        m.put("paymentMode", e.getPaymentMode());
        m.put("approvalStatus", e.getApprovalStatus());
        return m;
    }

    private Map<String, Object> toMap(InvoiceEntity i) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("invoiceName", i.getInvoiceName());
        m.put("billingMonth", i.getBillingMonth());
        m.put("clientCompanyName", i.getClientCompanyName());
        m.put("consultantEmail", i.getConsultantEmail());
        m.put("totalHt", i.getTotalHt());
        m.put("totalTtc", i.getTotalTtc());
        m.put("currency", i.getCurrency());
        m.put("paymentStatus", i.getPaymentStatus());
        m.put("paymentDueDate", i.getPaymentDueDate() != null ? i.getPaymentDueDate().toString() : null);
        m.put("sentDate", i.getSentDate() != null ? i.getSentDate().toString() : null);
        return m;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean containsIgnoreCase(String s, String needle) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
    }
}
