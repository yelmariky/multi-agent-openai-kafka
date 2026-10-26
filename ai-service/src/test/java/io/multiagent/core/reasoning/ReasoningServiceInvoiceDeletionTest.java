package io.multiagent.core.reasoning;

import io.multiagent.core.expense.service.DeleteExpenseService;
import io.multiagent.core.expense.service.RAGService;
import io.multiagent.core.model.IntentResult;
import io.multiagent.core.model.ReasoningResult;
import io.multiagent.core.reasoning.service.IntentClassifierService;
import io.multiagent.core.reasoning.service.ReasoningService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReasoningService — suppression de facture désactivée (annulation par avoir)")
class ReasoningServiceInvoiceDeletionTest {

    @Mock IntentClassifierService intentClassifierService;
    @Mock RAGService ragService;
    @Mock DeleteExpenseService deleteExpenseService;
    @org.mockito.Spy io.multiagent.core.i18n.Messages messages = io.multiagent.core.i18n.MessagesFixture.standard();
    @InjectMocks ReasoningService service;

    @Test
    void demandeDeSuppressionDeFacture_refuseeAvecRenvoiVersAvoir() {
        when(intentClassifierService.classify(anyString()))
                .thenReturn(IntentResult.builder().intent("delete_invoice").confidence(0.95).build());

        ReasoningResult result = service.process("supprime la facture F-202610-01");

        assertThat(result.getType()).isEqualTo("error");
        assertThat(result.getRaw()).contains("avoir").contains("console admin");
        verifyNoInteractions(ragService, deleteExpenseService);
    }
}
