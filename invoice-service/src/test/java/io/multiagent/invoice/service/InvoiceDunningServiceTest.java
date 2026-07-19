package io.multiagent.invoice.service;

import io.multiagent.invoice.entity.InvoiceEntity;
import io.multiagent.invoice.repository.InvoiceDunningLogJpaRepository;
import io.multiagent.invoice.repository.InvoiceJpaRepository;
import io.multiagent.invoice.repository.SellerProfileJpaRepository;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InvoiceDunningService — relances de factures impayées")
class InvoiceDunningServiceTest {

    @Mock InvoiceJpaRepository invoiceRepo;
    @Mock InvoiceDunningLogJpaRepository dunningRepo;
    @Mock SellerProfileJpaRepository sellerProfileRepo;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock JavaMailSender mailSender;

    InvoiceDunningService service;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new InvoiceDunningService(invoiceRepo, dunningRepo, sellerProfileRepo, jdbcTemplate, mailSender);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "fromAddress", "noreply@ia-insightservices.fr");
    }

    private InvoiceEntity invoice(String status, int daysLate) {
        InvoiceEntity inv = new InvoiceEntity();
        inv.setId(UUID.randomUUID());
        inv.setTenantId(tenantId);
        inv.setInvoiceName("F-202607-01");
        inv.setClientCompanyName("ACME");
        inv.setTotalTtc(new BigDecimal("1200.00"));
        inv.setPaymentStatus(status);
        inv.setPaymentDueDate(LocalDate.now().minusDays(daysLate));
        return inv;
    }

    private void stubClientEmail(String email) {
        when(jdbcTemplate.queryForList(contains("contact_email"), eq(String.class), any(), any()))
                .thenReturn(email == null ? List.of() : List.of(email));
    }

    @Test
    @DisplayName("facture ENVOYEE échue de 16j, email présent → R2 envoyée + tracée + statut EN_RETARD")
    void sendsSecondStageAndLogs() throws Exception {
        InvoiceEntity inv = invoice("ENVOYEE", 16);
        when(jdbcTemplate.queryForList(contains("FROM organization"), eq(UUID.class))).thenReturn(List.of(tenantId));
        when(invoiceRepo.findByTenantId(tenantId)).thenReturn(List.of(inv));
        when(dunningRepo.existsByInvoiceIdAndStage(inv.getId(), 2)).thenReturn(false);
        stubClientEmail("compta@acme.fr");
        when(sellerProfileRepo.findByTenantId(any())).thenReturn(Optional.empty());
        when(mailSender.createMimeMessage()).thenReturn(mock(MimeMessage.class));

        var results = service.runAll();

        assertThat(results).hasSize(1);
        assertThat(results.get(0)).containsEntry("status", "SENT").containsEntry("stage", 2);
        verify(mailSender).send(any(MimeMessage.class));
        verify(dunningRepo).save(argThat(l -> l.getStage() == 2 && "compta@acme.fr".equals(l.getRecipient())));
        assertThat(inv.getPaymentStatus()).isEqualTo("EN_RETARD");
    }

    @Test
    @DisplayName("palier déjà envoyé → pas de nouvel envoi (anti-doublon)")
    void skipsWhenStageAlreadySent() {
        InvoiceEntity inv = invoice("EN_RETARD", 16);
        when(jdbcTemplate.queryForList(contains("FROM organization"), eq(UUID.class))).thenReturn(List.of(tenantId));
        when(invoiceRepo.findByTenantId(tenantId)).thenReturn(List.of(inv));
        when(dunningRepo.existsByInvoiceIdAndStage(inv.getId(), 2)).thenReturn(true);

        var results = service.runAll();

        assertThat(results).isEmpty();
        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("facture payée ou en attente → jamais relancée")
    void neverDunsPaidOrPending() {
        when(jdbcTemplate.queryForList(contains("FROM organization"), eq(UUID.class))).thenReturn(List.of(tenantId));
        when(invoiceRepo.findByTenantId(tenantId))
                .thenReturn(List.of(invoice("PAYEE", 40), invoice("EN_ATTENTE", 40)));

        assertThat(service.runAll()).isEmpty();
        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("échéance à J+1 (< seuil R1 de 3j) → pas encore de relance")
    void noStageBeforeThreeDays() {
        when(jdbcTemplate.queryForList(contains("FROM organization"), eq(UUID.class))).thenReturn(List.of(tenantId));
        when(invoiceRepo.findByTenantId(tenantId)).thenReturn(List.of(invoice("ENVOYEE", 1)));

        assertThat(service.runAll()).isEmpty();
        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("pas d'email client → statut NO_EMAIL, aucun envoi")
    void noClientEmailSkips() {
        InvoiceEntity inv = invoice("ENVOYEE", 5);
        when(jdbcTemplate.queryForList(contains("FROM organization"), eq(UUID.class))).thenReturn(List.of(tenantId));
        when(invoiceRepo.findByTenantId(tenantId)).thenReturn(List.of(inv));
        when(dunningRepo.existsByInvoiceIdAndStage(inv.getId(), 1)).thenReturn(false);
        stubClientEmail(null);

        var results = service.runAll();

        assertThat(results.get(0)).containsEntry("status", "NO_EMAIL");
        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("relance manuelle d'une facture payée → SKIPPED_PAID")
    void manualDunningOnPaidInvoice() {
        InvoiceEntity inv = invoice("PAYEE", 40);
        when(invoiceRepo.findById(inv.getId())).thenReturn(Optional.of(inv));

        var result = service.dunManually(tenantId, inv.getId());

        assertThat(result).containsEntry("status", "SKIPPED_PAID");
        verifyNoInteractions(mailSender);
    }
}
