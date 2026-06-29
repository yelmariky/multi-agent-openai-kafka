package io.multiagent.invoice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.multiagent.invoice.client.LLMAIClient;
import io.multiagent.invoice.entity.InvoiceEntity;
import io.multiagent.invoice.infrastructure.tenant.TenantContext;
import io.multiagent.invoice.repository.InvoiceJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeleteInvoiceService — suppression factures")
class DeleteInvoiceServiceTest {

    @Mock InvoiceJpaRepository invoiceRepo;
    @Mock LLMAIClient          llm;

    private DeleteInvoiceService service;
    private static final UUID   TENANT = UUID.randomUUID();
    private static final String REALM  = "test-realm";

    @BeforeEach
    void setUp() {
        service = new DeleteInvoiceService(invoiceRepo, llm, new ObjectMapper());
        service.loadPrompts();
        TenantContext.set(TENANT, REALM);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("sellerCompanyName absent → erreur sans suppression")
    void missingSellerCompanyReturnsError() throws Exception {
        mockLlm("{\"invoiceName\":null,\"billingMonth\":\"2026-06\",\"sellerCompanyName\":null}");

        Map<String, Object> result = service.deleteByText("supprimer facture juin");

        assertThat(result).containsKey("error");
        assertThat(result.get("deleted")).isEqualTo(0);
        verify(invoiceRepo, never()).deleteAll(any());
    }

    @Test
    @DisplayName("billingMonth ET invoiceName absents → erreur")
    void missingMonthAndNameReturnsError() throws Exception {
        mockLlm("{\"invoiceName\":null,\"billingMonth\":null,\"sellerCompanyName\":\"IA-INSIGHT\"}");

        Map<String, Object> result = service.deleteByText("supprimer facture IA-INSIGHT");

        assertThat(result).containsKey("error");
        assertThat(result.get("deleted")).isEqualTo(0);
    }

    @Test
    @DisplayName("Facture trouvée par billingMonth → supprimée, deleted=1")
    void deleteByBillingMonthDeletesInvoice() throws Exception {
        mockLlm("{\"invoiceName\":null,\"billingMonth\":\"2026-06\",\"sellerCompanyName\":\"IA-INSIGHT\"}");
        InvoiceEntity inv = new InvoiceEntity();
        inv.setId(UUID.randomUUID());
        when(invoiceRepo.findByTenantIdAndBillingMonthAndSellerCompanyNameIgnoreCase(
                TENANT, "2026-06", "IA-INSIGHT"))
            .thenReturn(List.of(inv));

        Map<String, Object> result = service.deleteByText("supprimer facture juin IA-INSIGHT");

        assertThat(result.get("deleted")).isEqualTo(1);
        verify(invoiceRepo).deleteAll(List.of(inv));
    }

    @Test
    @DisplayName("Facture introuvable → deleted=0 avec message erreur")
    void invoiceNotFoundReturnsZeroDeleted() throws Exception {
        mockLlm("{\"invoiceName\":null,\"billingMonth\":\"2026-06\",\"sellerCompanyName\":\"IA-INSIGHT\"}");
        // findByTenantIdAndBillingMonth... retourne liste vide par défaut (Mockito default)

        Map<String, Object> result = service.deleteByText("supprimer facture juin IA-INSIGHT");

        assertThat(result.get("deleted")).isEqualTo(0);
        assertThat(result).containsKey("error");
        verify(invoiceRepo, never()).deleteAll(any());
    }

    @Test
    @DisplayName("LLM en erreur → deleted=0, pas de crash")
    void llmErrorReturnsZeroDeleted() {
        when(llm.chatJson(any(), any(), any())).thenThrow(new RuntimeException("LLM down"));

        Map<String, Object> result = service.deleteByText("supprimer facture");

        assertThat(result.get("deleted")).isEqualTo(0);
        assertThat(result).containsKey("error");
    }

    private void mockLlm(String json) throws Exception {
        var completion = mock(com.openai.models.chat.completions.ChatCompletion.class);
        var choice = mock(com.openai.models.chat.completions.ChatCompletion.Choice.class);
        var message = mock(com.openai.models.chat.completions.ChatCompletionMessage.class);
        when(completion.choices()).thenReturn(List.of(choice));
        when(choice.message()).thenReturn(message);
        when(message.content()).thenReturn(Optional.of(json));
        when(llm.chatJson(any(), any(), any())).thenReturn(completion);
    }
}
