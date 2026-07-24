package io.multiagent.invoice.controller;

import io.multiagent.invoice.entity.InvoiceEntity;
import io.multiagent.invoice.repository.InvoiceJpaRepository;
import io.multiagent.invoice.service.InvoiceDunningService;
import io.multiagent.invoice.service.InvoicePaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformInvoiceController — factures d'abonnement côté console plateforme")
class PlatformInvoiceControllerTest {

    @Mock InvoiceJpaRepository invoiceRepo;
    @Mock InvoicePaymentService invoicePaymentService;
    @Mock InvoiceDunningService invoiceDunningService;
    @Mock JdbcTemplate jdbcTemplate;

    PlatformInvoiceController controller;

    private final UUID sellerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new PlatformInvoiceController(
                invoiceRepo, invoicePaymentService, invoiceDunningService, jdbcTemplate, "ia-insight");
    }

    private void mockSeller() {
        when(jdbcTemplate.queryForObject(contains("FROM organization"), eq(UUID.class), eq("ia-insight")))
                .thenReturn(sellerId);
    }

    private InvoiceEntity invoice(String name, String status, LocalDate due) {
        InvoiceEntity inv = new InvoiceEntity();
        inv.setId(UUID.randomUUID());
        inv.setTenantId(sellerId);
        inv.setInvoiceName(name);
        inv.setClientCompanyName("ITHECHIA");
        inv.setTotalTtc(new BigDecimal("564.48"));
        inv.setPaymentStatus(status);
        inv.setPaymentDueDate(due);
        inv.setInvoiceDate(LocalDate.now());
        return inv;
    }

    @Test
    @DisplayName("liste : uniquement les factures ABO-*, statut EN_RETARD calculé, relance exposée")
    void listsOnlySubscriptionInvoicesWithDynamicStatus() {
        mockSeller();
        InvoiceEntity abo = invoice("ABO-202607-ITHECHIA", "ENVOYEE", LocalDate.now().minusDays(10));
        InvoiceEntity presta = invoice("F-202607-01", "ENVOYEE", LocalDate.now().minusDays(10));
        when(invoiceRepo.findByTenantId(sellerId)).thenReturn(List.of(abo, presta));
        when(jdbcTemplate.queryForList(contains("invoice_dunning_log"), eq(sellerId)))
                .thenReturn(List.of(Map.of("invoice_id", abo.getId(), "stage", 1, "sent_at", "2026-07-15")));

        ResponseEntity<?> res = controller.subscriptionInvoices();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = (List<Map<String, Object>>) res.getBody();
        assertThat(body).hasSize(1);   // la facture de prestation F-* est exclue
        assertThat(body.get(0))
                .containsEntry("invoiceName", "ABO-202607-ITHECHIA")
                .containsEntry("paymentStatus", "EN_RETARD")     // échue → statut dynamique
                .containsEntry("lastDunningStage", 1);
    }

    @Test
    @DisplayName("organisation vendeuse introuvable → 503 explicite")
    void missingSellerGives503() {
        when(jdbcTemplate.queryForObject(anyString(), eq(UUID.class), eq("ia-insight")))
                .thenThrow(new RuntimeException("no rows"));

        assertThat(controller.subscriptionInvoices().getStatusCode().value()).isEqualTo(503);
        assertThat(controller.dun(UUID.randomUUID()).getStatusCode().value()).isEqualTo(503);
    }

    @Test
    @DisplayName("mark-sent / mark-paid : exécutés avec le TenantContext du vendeur")
    void markActionsRunAsSeller() {
        mockSeller();
        UUID invId = UUID.randomUUID();
        when(invoicePaymentService.markSent(invId)).thenReturn(Map.of("status", "ENVOYEE"));
        when(invoicePaymentService.markPaid(eq(invId), isNull())).thenReturn(Map.of("status", "PAYEE"));

        assertThat(controller.markSent(invId).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.markPaid(invId, null).getStatusCode().value()).isEqualTo(200);
        verify(invoicePaymentService).markSent(invId);
        verify(invoicePaymentService).markPaid(invId, null);
    }

    @Test
    @DisplayName("relance manuelle : déléguée au service dunning avec le tenant vendeur")
    void dunDelegatesToDunningService() {
        mockSeller();
        UUID invId = UUID.randomUUID();
        when(invoiceDunningService.dunManually(sellerId, invId))
                .thenReturn(Map.of("status", "SENT", "stage", 1));

        ResponseEntity<?> res = controller.dun(invId);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        verify(invoiceDunningService).dunManually(sellerId, invId);
    }
}
