package io.multiagent.invoice.service;

import io.multiagent.invoice.entity.InvoiceEntity;
import io.multiagent.invoice.infrastructure.tenant.TenantContext;
import io.multiagent.invoice.repository.InvoiceJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InvoiceCancellationService — annulation sans suppression")
class InvoiceCancellationServiceTest {

    private static final UUID TENANT = UUID.randomUUID();

    @Mock InvoiceJpaRepository invoiceRepo;
    @Mock InvoiceService invoiceService;
    @InjectMocks InvoiceCancellationService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT, "testme");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private InvoiceEntity invoice(String status, String type, UUID tenant) {
        InvoiceEntity e = new InvoiceEntity();
        e.setId(UUID.randomUUID());
        e.setTenantId(tenant);
        e.setInvoiceName("F-202611-01");
        e.setPaymentStatus(status);
        e.setInvoiceType(type);
        when(invoiceRepo.findById(e.getId())).thenReturn(Optional.of(e));
        return e;
    }

    @Test
    void brouillon_annuleSansAvoir() throws Exception {
        InvoiceEntity draft = invoice("EN_ATTENTE", "FACTURE", TENANT);

        Map<String, Object> result = service.cancel(draft.getId());

        assertThat(draft.getPaymentStatus()).isEqualTo("ANNULEE");
        assertThat(result).doesNotContainKey("creditNoteName");
        verify(invoiceService, never()).issueCreditNote(any());
        verify(invoiceRepo, never()).delete(any());
    }

    @Test
    void envoyee_annuleeParAvoir() throws Exception {
        InvoiceEntity sent = invoice("ENVOYEE", "FACTURE", TENANT);
        InvoiceEntity credit = new InvoiceEntity();
        credit.setId(UUID.randomUUID());
        credit.setInvoiceName("A-202610-01");
        when(invoiceService.issueCreditNote(sent)).thenReturn(credit);

        Map<String, Object> result = service.cancel(sent.getId());

        assertThat(sent.getPaymentStatus()).isEqualTo("ANNULEE");
        assertThat(result).containsEntry("creditNoteName", "A-202610-01");
    }

    @Test
    void enRetard_annuleeParAvoir() throws Exception {
        InvoiceEntity late = invoice("EN_RETARD", "FACTURE", TENANT);
        when(invoiceService.issueCreditNote(late)).thenReturn(new InvoiceEntity());

        service.cancel(late.getId());

        verify(invoiceService).issueCreditNote(late);
    }

    @Test
    void payee_refusee() {
        InvoiceEntity paid = invoice("PAYEE", "FACTURE", TENANT);
        assertThatThrownBy(() -> service.cancel(paid.getId()))
                .isInstanceOf(InvoiceService.InvoiceLockedException.class).hasMessageContaining("payée");
    }

    @Test
    void dejaAnnulee_refusee() {
        InvoiceEntity cancelled = invoice("ANNULEE", "FACTURE", TENANT);
        assertThatThrownBy(() -> service.cancel(cancelled.getId()))
                .isInstanceOf(InvoiceService.InvoiceLockedException.class);
    }

    @Test
    void avoir_nonAnnulable() {
        InvoiceEntity credit = invoice("EMIS", "AVOIR", TENANT);
        assertThatThrownBy(() -> service.cancel(credit.getId()))
                .isInstanceOf(InvoiceService.InvoiceLockedException.class);
    }

    @Test
    void autreTenant_introuvable() {
        InvoiceEntity other = invoice("EN_ATTENTE", "FACTURE", UUID.randomUUID());
        assertThatThrownBy(() -> service.cancel(other.getId()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
