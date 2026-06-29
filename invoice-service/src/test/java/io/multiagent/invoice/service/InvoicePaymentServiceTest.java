package io.multiagent.invoice.service;

import io.multiagent.invoice.entity.InvoiceEntity;
import io.multiagent.invoice.infrastructure.tenant.TenantContext;
import io.multiagent.invoice.repository.InvoiceJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InvoicePaymentService — suivi paiement")
class InvoicePaymentServiceTest {

    @Mock InvoiceJpaRepository invoiceRepo;
    @InjectMocks InvoicePaymentService service;

    private static final UUID TENANT = UUID.randomUUID();

    // ── markSent ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("markSent() — status ENVOYEE + sentDate = aujourd'hui")
    void markSent_setsStatusAndDate() {
        InvoiceEntity inv = invoice(UUID.randomUUID(), null);
        try (MockedStatic<TenantContext> tc = mockStatic(TenantContext.class)) {
            tc.when(TenantContext::getTenantId).thenReturn(TENANT);
            when(invoiceRepo.findById(inv.getId())).thenReturn(Optional.of(inv));
            when(invoiceRepo.save(any())).thenReturn(inv);

            Map<String, Object> result = service.markSent(inv.getId());

            assertThat(result.get("paymentStatus")).isEqualTo("ENVOYEE");
            assertThat(inv.getSentDate()).isEqualTo(LocalDate.now());
            verify(invoiceRepo).save(inv);
        }
    }

    @Test
    @DisplayName("markSent() — mauvais tenant lève IllegalArgumentException")
    void markSent_wrongTenant_throws() {
        InvoiceEntity inv = invoice(UUID.randomUUID(), UUID.randomUUID()); // autre tenant
        try (MockedStatic<TenantContext> tc = mockStatic(TenantContext.class)) {
            tc.when(TenantContext::getTenantId).thenReturn(TENANT);
            when(invoiceRepo.findById(inv.getId())).thenReturn(Optional.of(inv));

            assertThatThrownBy(() -> service.markSent(inv.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("introuvable");
        }
    }

    // ── markPaid ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("markPaid() — status PAYEE + date fournie conservée")
    void markPaid_withDate_usesProvidedDate() {
        InvoiceEntity inv = invoice(UUID.randomUUID(), null);
        try (MockedStatic<TenantContext> tc = mockStatic(TenantContext.class)) {
            tc.when(TenantContext::getTenantId).thenReturn(TENANT);
            when(invoiceRepo.findById(inv.getId())).thenReturn(Optional.of(inv));
            when(invoiceRepo.save(any())).thenReturn(inv);

            service.markPaid(inv.getId(), "2026-07-15");

            assertThat(inv.getPaymentStatus()).isEqualTo("PAYEE");
            assertThat(inv.getPaymentReceivedDate()).isEqualTo(LocalDate.of(2026, 7, 15));
        }
    }

    @Test
    @DisplayName("markPaid() — date null → today utilisé")
    void markPaid_noDate_usesToday() {
        InvoiceEntity inv = invoice(UUID.randomUUID(), null);
        try (MockedStatic<TenantContext> tc = mockStatic(TenantContext.class)) {
            tc.when(TenantContext::getTenantId).thenReturn(TENANT);
            when(invoiceRepo.findById(inv.getId())).thenReturn(Optional.of(inv));
            when(invoiceRepo.save(any())).thenReturn(inv);

            service.markPaid(inv.getId(), null);

            assertThat(inv.getPaymentReceivedDate()).isEqualTo(LocalDate.now());
        }
    }

    @Test
    @DisplayName("markPaid() — date invalide → today utilisé")
    void markPaid_invalidDate_usesToday() {
        InvoiceEntity inv = invoice(UUID.randomUUID(), null);
        try (MockedStatic<TenantContext> tc = mockStatic(TenantContext.class)) {
            tc.when(TenantContext::getTenantId).thenReturn(TENANT);
            when(invoiceRepo.findById(inv.getId())).thenReturn(Optional.of(inv));
            when(invoiceRepo.save(any())).thenReturn(inv);

            service.markPaid(inv.getId(), "not-a-date");

            assertThat(inv.getPaymentReceivedDate()).isEqualTo(LocalDate.now());
        }
    }

    // ── list ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("list() — filtre par statut EN_ATTENTE")
    void list_filtersByStatus() {
        InvoiceEntity enAttente = invoice(UUID.randomUUID(), null);
        enAttente.setPaymentStatus("EN_ATTENTE");
        InvoiceEntity payee = invoice(UUID.randomUUID(), null);
        payee.setPaymentStatus("PAYEE");
        payee.setPaymentReceivedDate(LocalDate.now());

        try (MockedStatic<TenantContext> tc = mockStatic(TenantContext.class)) {
            tc.when(TenantContext::getTenantId).thenReturn(TENANT);
            when(invoiceRepo.findByTenantId(TENANT)).thenReturn(List.of(enAttente, payee));

            List<Map<String, Object>> result = service.list("EN_ATTENTE");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).get("paymentStatus")).isEqualTo("EN_ATTENTE");
        }
    }

    @Test
    @DisplayName("list() — facture en retard détectée dynamiquement")
    void list_detectsOverdueInvoice() {
        InvoiceEntity overdue = invoice(UUID.randomUUID(), null);
        overdue.setPaymentStatus("EN_ATTENTE");
        overdue.setPaymentDueDate(LocalDate.now().minusDays(5)); // échue il y a 5 jours

        try (MockedStatic<TenantContext> tc = mockStatic(TenantContext.class)) {
            tc.when(TenantContext::getTenantId).thenReturn(TENANT);
            when(invoiceRepo.findByTenantId(TENANT)).thenReturn(List.of(overdue));

            List<Map<String, Object>> result = service.list(null);

            assertThat(result.get(0).get("paymentStatus")).isEqualTo("EN_RETARD");
        }
    }

    @Test
    @DisplayName("list() — facture PAYEE non marquée EN_RETARD même si date passée")
    void list_paidInvoiceNotMarkedOverdue() {
        InvoiceEntity paid = invoice(UUID.randomUUID(), null);
        paid.setPaymentStatus("PAYEE");
        paid.setPaymentDueDate(LocalDate.now().minusDays(10));
        paid.setPaymentReceivedDate(LocalDate.now().minusDays(1));

        try (MockedStatic<TenantContext> tc = mockStatic(TenantContext.class)) {
            tc.when(TenantContext::getTenantId).thenReturn(TENANT);
            when(invoiceRepo.findByTenantId(TENANT)).thenReturn(List.of(paid));

            List<Map<String, Object>> result = service.list(null);

            assertThat(result.get(0).get("paymentStatus")).isEqualTo("PAYEE");
        }
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private InvoiceEntity invoice(UUID id, UUID tenantId) {
        InvoiceEntity e = new InvoiceEntity();
        e.setId(id);
        e.setTenantId(tenantId != null ? tenantId : TENANT);
        e.setTotalTtc(BigDecimal.valueOf(12000));
        e.setInvoiceName("F-202607-01");
        e.setBillingMonth("2026-06");
        return e;
    }
}
