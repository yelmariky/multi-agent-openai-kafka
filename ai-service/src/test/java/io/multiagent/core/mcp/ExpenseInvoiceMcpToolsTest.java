package io.multiagent.core.mcp;

import io.multiagent.core.infrastructure.tenant.TenantContext;
import io.multiagent.core.invoice.entity.InvoiceEntity;
import io.multiagent.core.invoice.repository.InvoiceJpaRepository;
import io.multiagent.core.model.ExpenseItem;
import io.multiagent.core.service.WeaviateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpenseInvoiceMcpToolsTest {

    @Mock
    private WeaviateService weaviateService;
    @Mock
    private InvoiceJpaRepository invoiceRepository;
    @Mock
    private McpSessionTenantRegistry sessionTenantRegistry;

    private ExpenseInvoiceMcpTools tools;

    @BeforeEach
    void setUp() {
        tools = new ExpenseInvoiceMcpTools(weaviateService, invoiceRepository, sessionTenantRegistry, io.multiagent.core.i18n.MessagesFixture.standard());
        // ThreadLocal déjà posé (chemin rapide) — le ToolContext/registry de secours n'est pas sollicité.
        TenantContext.set(UUID.randomUUID(), "ia-insight");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static ExpenseItem expense(Double amount, String paymentMode, String approvalStatus) {
        ExpenseItem e = new ExpenseItem();
        e.setAmount(amount);
        e.setPaymentMode(paymentMode);
        e.setApprovalStatus(approvalStatus);
        e.setCurrency("EUR");
        return e;
    }

    @Test
    void getExpenseSummaryAggregatesByPaymentModeAndStatus() {
        List<ExpenseItem> expenses = List.of(
                expense(100.0, "Personnel", "APPROVED"),
                expense(50.0, "Business", "PENDING"),
                expense(30.0, "Personnel", "REFUSED"),
                expense(20.0, "Business", "APPROVED")
        );
        when(weaviateService.findExpensesBetween(any(), any())).thenReturn(expenses);

        Map<String, Object> summary = tools.get_expense_summary("2026-09", null);

        assertThat(summary.get("count")).isEqualTo(4);
        assertThat(summary.get("totalPersonnel")).isEqualTo(130.0);
        assertThat(summary.get("totalBusiness")).isEqualTo(70.0);
        assertThat(summary.get("approved")).isEqualTo(2L);
        assertThat(summary.get("pending")).isEqualTo(1L);
        assertThat(summary.get("refused")).isEqualTo(1L);
    }

    @Test
    void listExpensesFiltersByApprovalStatus() {
        List<ExpenseItem> expenses = List.of(
                expense(10.0, "Personnel", "APPROVED"),
                expense(20.0, "Personnel", "PENDING")
        );
        when(weaviateService.findExpensesBetween(any(), any(), any(), any(), any())).thenReturn(expenses);

        List<Map<String, Object>> result = tools.list_expenses(
                "2026-09-01", "2026-09-30", null, null, "APPROVED", null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("amount")).isEqualTo(10.0);
    }

    @Test
    void listExpensesWithoutStatusFilterReturnsAll() {
        List<ExpenseItem> expenses = List.of(
                expense(10.0, "Personnel", "APPROVED"),
                expense(20.0, "Personnel", "PENDING")
        );
        when(weaviateService.findExpensesBetween(any(), any(), any(), any(), any())).thenReturn(expenses);

        List<Map<String, Object>> result = tools.list_expenses("2026-09-01", "2026-09-30", null, null, null, null);

        assertThat(result).hasSize(2);
    }

    @Test
    void getInvoiceStatusReturnsErrorWhenNotFound() {
        when(invoiceRepository.findByTenantIdAndInvoiceNameIgnoreCase(any(), any())).thenReturn(List.of());

        Map<String, Object> result = tools.get_invoice_status("F-INCONNUE", null);

        assertThat(result).containsEntry("error", "Facture introuvable : F-INCONNUE");
    }

    @Test
    void getInvoiceStatusReturnsInvoiceDetails() {
        InvoiceEntity inv = new InvoiceEntity();
        inv.setInvoiceName("F-202610-01");
        inv.setClientCompanyName("Infogene Digital");
        inv.setPaymentStatus("EN_ATTENTE");
        inv.setBillingMonth("2026-09");
        when(invoiceRepository.findByTenantIdAndInvoiceNameIgnoreCase(any(), any())).thenReturn(List.of(inv));

        Map<String, Object> result = tools.get_invoice_status("F-202610-01", null);

        assertThat(result).containsEntry("invoiceName", "F-202610-01")
                .containsEntry("clientCompanyName", "Infogene Digital")
                .containsEntry("paymentStatus", "EN_ATTENTE");
    }

    @Test
    void listInvoicesFiltersByClientWhenProvided() {
        when(invoiceRepository.findByTenantIdAndBillingMonthBetweenAndClientCompanyNameIgnoreCase(
                any(), any(), any(), any())).thenReturn(List.of());

        tools.list_invoices("2026-09", "2026-09", "Infogene Digital", null, null);

        verify(invoiceRepository)
                .findByTenantIdAndBillingMonthBetweenAndClientCompanyNameIgnoreCase(
                        any(), any(), any(), any());
    }

    @Test
    void fallsBackToSessionRegistryWhenThreadLocalEmpty() {
        // Simule le transport SSE : l'exécution de l'outil se produit sur un thread sans
        // TenantContext posé (ThreadLocal vide) — doit retomber sur le registre de session.
        TenantContext.clear();
        UUID fallbackTenantId = UUID.randomUUID();
        when(sessionTenantRegistry.get(null)).thenReturn(fallbackTenantId);
        when(invoiceRepository.findByTenantIdAndInvoiceNameIgnoreCase(any(), any())).thenReturn(List.of());

        tools.get_invoice_status("F-TEST", null);

        verify(invoiceRepository).findByTenantIdAndInvoiceNameIgnoreCase(fallbackTenantId, "F-TEST");
    }

    @Test
    void throwsExplicitErrorWhenNoTenantResolvable() {
        TenantContext.clear();
        when(sessionTenantRegistry.get(null)).thenReturn(null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tools.get_invoice_status("F-TEST", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Tenant introuvable");
    }

}
