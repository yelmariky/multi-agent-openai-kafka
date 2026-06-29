package io.multiagent.core.reasoning;

import io.multiagent.core.client.LLMAIClient;
import io.multiagent.core.exception.LLMClientException;
import io.multiagent.core.model.IntentResult;
import io.multiagent.core.reasoning.service.IntentClassifierService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("IntentClassifierService — classification + fallback km")
class IntentClassifierServiceTest {

    @Mock LLMAIClient llm;
    @InjectMocks IntentClassifierService service;

    @BeforeEach
    void loadPrompts() {
        // Injecter des prompts minimaux pour éviter le chargement du classpath
        ReflectionTestUtils.setField(service, "systemPromptEnv",  "Réponds en JSON.");
        ReflectionTestUtils.setField(service, "examplesPromptEnv", "USER: test\nOUTPUT: {\"intent\":\"create_expense\"}");
        service.loadPrompts();
    }

    @Nested
    @DisplayName("Cas LLM fonctionnel")
    class WithLlm {
        @Test
        @DisplayName("classify() retourne l'intent extrait du JSON LLM")
        void classify_returnsParsedIntent() {
            when(llm.extractJSON(any(), any()))
                .thenReturn("{\"intent\":\"create_expense\",\"confidence\":0.95}");

            IntentResult r = service.classify("repas 45€ restaurant Paris");
            assertThat(r.getIntent()).isEqualTo("create_expense");
            assertThat(r.getConfidence()).isEqualTo(0.95);
        }

        @Test
        @DisplayName("classify() retourne unknown sur JSON inattendu")
        void classify_unknownIntentOnEmptyJson() {
            when(llm.extractJSON(any(), any())).thenReturn("{}");
            IntentResult r = service.classify("texte quelconque");
            assertThat(r.getIntent()).isEqualTo("unknown");
        }
    }

    @Nested
    @DisplayName("Fallback règle km — LLM indisponible")
    class KmFallback {
        @BeforeEach
        void llmFails() {
            when(llm.extractJSON(any(), any()))
                .thenThrow(new LLMClientException("Groq down"));
        }

        @Test
        @DisplayName("Texte km → create_expense via règle regex")
        void kmText_returnCreateExpenseIntent() {
            IntentResult r = service.classify("Frais kilométriques juin, 44 km aller-retour domicile-bureau par jour");
            assertThat(r.getIntent()).isEqualTo("create_expense");
            assertThat(r.getConfidence()).isGreaterThan(0.8);
        }

        @Test
        @DisplayName("Texte km avec chiffre → détecté")
        void kmWithNumber_detected() {
            IntentResult r = service.classify("50 km trajet domicile travail");
            assertThat(r.getIntent()).isEqualTo("create_expense");
        }

        @Test
        @DisplayName("Texte kilomètre → détecté")
        void kilometreWord_detected() {
            IntentResult r = service.classify("kilomètre parcouru 30 ce mois");
            assertThat(r.getIntent()).isEqualTo("create_expense");
        }

        @Test
        @DisplayName("Texte non-km → error quand LLM down")
        void nonKmText_returnsErrorWhenLlmDown() {
            IntentResult r = service.classify("restaurant sushi 35€");
            assertThat(r.getIntent()).isEqualTo("error");
            assertThat(r.getConfidence()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("km sans chiffre → non détecté → error")
        void kmWithoutNumber_notDetected() {
            IntentResult r = service.classify("frais kilométriques");
            assertThat(r.getIntent()).isEqualTo("error");
        }
    }
}
