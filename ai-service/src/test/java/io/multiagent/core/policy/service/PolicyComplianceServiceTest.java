package io.multiagent.core.policy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.multiagent.core.client.LLMAIClient;
import io.multiagent.core.model.ExpenseItem;
import io.multiagent.core.policy.entity.PolicyDocumentEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicyComplianceServiceTest {

    private static final String EXPENSE_REIMBURSEMENT = "EXPENSE_REIMBURSEMENT";

    @Mock
    private LLMAIClient llm;
    @Mock
    private PolicyIngestionService policyIngestionService;

    private PolicyComplianceService service;

    @BeforeEach
    void setUp() {
        service = new PolicyComplianceService(llm, policyIngestionService, new ObjectMapper(), io.multiagent.core.i18n.MessagesFixture.standard());
        ReflectionTestUtils.setField(service, "expenseExtractionModel", "test-model");
        ReflectionTestUtils.setField(service, "policyComplianceUserTemplate", "POLICY:\n{policy}\nEXPENSE: {type} {amount} {currency} {description}");
        ReflectionTestUtils.setField(service, "kmDailyCapUserTemplate", "POLICY:\n{policy}");
    }

    private static ExpenseItem expense(String type, Double amount) {
        ExpenseItem e = new ExpenseItem();
        e.setType(type);
        e.setAmount(amount);
        e.setCurrency("EUR");
        e.setDescription("Déjeuner client");
        return e;
    }

    @Test
    void returnsNullWhenPromptNotConfigured() {
        ReflectionTestUtils.setField(service, "policyCompliancePromptTemplate", null);

        String result = service.evaluate(expense("restaurant", 50.0));

        assertThat(result).isNull();
    }

    @Test
    void returnsNullWhenNoActivePolicy() {
        ReflectionTestUtils.setField(service, "policyCompliancePromptTemplate", "Tu es un évaluateur.");
        when(policyIngestionService.findActivePolicy(EXPENSE_REIMBURSEMENT)).thenReturn(Optional.empty());

        String result = service.evaluate(expense("restaurant", 50.0));

        assertThat(result).isNull();
    }

    @Test
    void returnsVerdictJsonWhenPolicyActiveAndLlmSucceeds() {
        ReflectionTestUtils.setField(service, "policyCompliancePromptTemplate", "Tu es un évaluateur.");
        PolicyDocumentEntity policy = new PolicyDocumentEntity();
        policy.setRawText("Les repas sont plafonnés à 40 euros.");
        when(policyIngestionService.findActivePolicy(EXPENSE_REIMBURSEMENT)).thenReturn(Optional.of(policy));
        when(llm.extractJSONWithModel(anyString(), anyString(), anyString()))
                .thenReturn("{\"compliant\":false,\"citedClause\":\"plafonnés à 40 euros\",\"explanation\":\"Dépasse le plafond\"}");

        String result = service.evaluate(expense("restaurant", 106.80));

        assertThat(result).contains("\"compliant\":false").contains("plafonnés à 40 euros");
    }

    @Test
    void returnsNullWhenLlmThrows() {
        ReflectionTestUtils.setField(service, "policyCompliancePromptTemplate", "Tu es un évaluateur.");
        PolicyDocumentEntity policy = new PolicyDocumentEntity();
        policy.setRawText("Les repas sont plafonnés à 40 euros.");
        when(policyIngestionService.findActivePolicy(EXPENSE_REIMBURSEMENT)).thenReturn(Optional.of(policy));
        when(llm.extractJSONWithModel(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Groq down"));

        String result = service.evaluate(expense("restaurant", 50.0));

        assertThat(result).isNull();
    }

    @Test
    void returnsNullWhenLlmReturnsInvalidJson() {
        ReflectionTestUtils.setField(service, "policyCompliancePromptTemplate", "Tu es un évaluateur.");
        PolicyDocumentEntity policy = new PolicyDocumentEntity();
        policy.setRawText("Les repas sont plafonnés à 40 euros.");
        when(policyIngestionService.findActivePolicy(EXPENSE_REIMBURSEMENT)).thenReturn(Optional.of(policy));
        when(llm.extractJSONWithModel(anyString(), anyString(), anyString())).thenReturn("not json");

        String result = service.evaluate(expense("restaurant", 50.0));

        assertThat(result).isNull();
    }

    private PolicyDocumentEntity activeKmPolicy() {
        ReflectionTestUtils.setField(service, "kmDailyCapPromptTemplate", "Extrais le plafond km.");
        PolicyDocumentEntity policy = new PolicyDocumentEntity();
        policy.setId(UUID.randomUUID());
        policy.setRawText("- les frais km ne doit pas dépasser 29 euros par jour");
        when(policyIngestionService.findActivePolicy(EXPENSE_REIMBURSEMENT)).thenReturn(Optional.of(policy));
        return policy;
    }

    @Test
    void kmDailyCapExtractedOncePerPolicyVersion() {
        activeKmPolicy();
        when(llm.extractJSONWithModel(anyString(), anyString(), anyString()))
                .thenReturn("{\"kmDailyCapEur\":29,\"citedClause\":\"29 euros par jour\"}");

        Optional<PolicyComplianceService.KmDailyCap> first = service.findKmDailyCap();
        Optional<PolicyComplianceService.KmDailyCap> second = service.findKmDailyCap();

        assertThat(first).contains(new PolicyComplianceService.KmDailyCap(29.0, "29 euros par jour"));
        assertThat(second).isEqualTo(first);
        verify(llm, times(1)).extractJSONWithModel(anyString(), anyString(), anyString());
    }

    @Test
    void kmDailyCapEmptyWhenPolicyHasNoKmRule() {
        activeKmPolicy();
        when(llm.extractJSONWithModel(anyString(), anyString(), anyString()))
                .thenReturn("{\"kmDailyCapEur\":null,\"citedClause\":null}");

        assertThat(service.findKmDailyCap()).isEmpty();
    }

    @Test
    void kmDailyCapEmptyWhenPromptNotConfigured() {
        ReflectionTestUtils.setField(service, "kmDailyCapPromptTemplate", "");

        assertThat(service.findKmDailyCap()).isEmpty();
    }

    @Test
    void kmDailyCapEmptyWhenNoActivePolicy() {
        ReflectionTestUtils.setField(service, "kmDailyCapPromptTemplate", "Extrais le plafond km.");
        when(policyIngestionService.findActivePolicy(EXPENSE_REIMBURSEMENT)).thenReturn(Optional.empty());

        assertThat(service.findKmDailyCap()).isEmpty();
    }

    @Test
    void kmDailyCapIgnoresNonPositiveValue() {
        activeKmPolicy();
        when(llm.extractJSONWithModel(anyString(), anyString(), anyString()))
                .thenReturn("{\"kmDailyCapEur\":-5,\"citedClause\":\"x\"}");

        assertThat(service.findKmDailyCap()).isEmpty();
    }

    @Test
    void kmCapVerdictJsonIsNonCompliantWithAmounts() {
        String json = service.kmCapVerdictJson(
                new PolicyComplianceService.KmDailyCap(29.0, "29 euros par jour"), 42.01, 21.27, 7.73);

        assertThat(json).contains("\"compliant\":false")
                .contains("\"capped\":true")
                .contains("\"citedClause\":\"29 euros par jour\"")
                .contains("Plafonné à 7,73 €").contains("plafond 29,00 €/jour")
                .contains("calculé 42,01 €").contains("déjà déclaré ce jour 21,27 €");
    }

    @Test
    void kmDailyCapLlmFailureIsNotCached() {
        activeKmPolicy();
        when(llm.extractJSONWithModel(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Groq down"))
                .thenReturn("{\"kmDailyCapEur\":29,\"citedClause\":null}");

        assertThat(service.findKmDailyCap()).isEmpty();
        assertThat(service.findKmDailyCap()).map(PolicyComplianceService.KmDailyCap::capEur).contains(29.0);
    }
}
