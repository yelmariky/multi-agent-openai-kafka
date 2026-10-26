package io.multiagent.core.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LlmModelsHealthIndicator — modèles retirés par le provider")
class LlmModelsHealthIndicatorTest {

    @Mock
    private LLMAIClient llm;

    private LlmModelsHealthIndicator indicator(String... models) {
        return new LlmModelsHealthIndicator(llm, models[0], models[1], models[2], models[3], models[4], models[5]);
    }

    private LlmModelsHealthIndicator standard() {
        return indicator("openai/gpt-oss-20b", "openai/gpt-oss-20b", "openai/gpt-oss-20b",
                "openai/gpt-oss-120b", "openai/gpt-oss-120b", "");
    }

    @Test
    void tousLesModelesPresents_up() {
        when(llm.usesAlternateProvider()).thenReturn(true);
        when(llm.listChatModels()).thenReturn(Set.of("openai/gpt-oss-20b", "openai/gpt-oss-120b", "qwen/qwen3.8-27b"));

        assertThat(standard().health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void modeleRetire_downAvecLaListe() {
        when(llm.usesAlternateProvider()).thenReturn(true);
        when(llm.listChatModels()).thenReturn(Set.of("openai/gpt-oss-120b"));

        Health h = indicator("llama-3.1-8b-instant", "llama-3.1-8b-instant", "llama-3.1-8b-instant",
                "openai/gpt-oss-120b", "openai/gpt-oss-120b", "").health();

        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        assertThat(h.getDetails().get("missing")).isEqualTo(List.of("llama-3.1-8b-instant"));
    }

    @Test
    void openAiSeul_upSansAppelProvider() {
        when(llm.usesAlternateProvider()).thenReturn(false);

        assertThat(standard().health().getStatus()).isEqualTo(Status.UP);
        verify(llm, never()).listChatModels();
    }

    @Test
    void erreurProvider_unknown() {
        when(llm.usesAlternateProvider()).thenReturn(true);
        when(llm.listChatModels()).thenThrow(new RuntimeException("timeout"));

        Health h = standard().health();

        assertThat(h.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(h.getDetails().get("error")).isEqualTo("timeout");
    }

    @Test
    void resultatMisEnCache() {
        when(llm.usesAlternateProvider()).thenReturn(true);
        when(llm.listChatModels()).thenReturn(Set.of("openai/gpt-oss-20b", "openai/gpt-oss-120b"));
        LlmModelsHealthIndicator indicator = standard();

        indicator.health();
        indicator.health();

        verify(llm, times(1)).listChatModels();
    }

    @Test
    void valeursVidesEtDoublonsIgnores() {
        when(llm.usesAlternateProvider()).thenReturn(true);
        when(llm.listChatModels()).thenReturn(Set.of("m1"));

        Health h = indicator(" m1 ", "m1", "", null, "m1", "  ").health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails().get("models")).isEqualTo(Set.of("m1"));
    }

    @Test
    void logCheck_parcourtLesTroisStatuts() {
        when(llm.usesAlternateProvider()).thenReturn(true);
        when(llm.listChatModels())
                .thenReturn(Set.of())                               // DOWN
                .thenThrow(new RuntimeException("x"))               // UNKNOWN
                .thenReturn(Set.of("openai/gpt-oss-20b", "openai/gpt-oss-120b")); // UP

        standard().logCheck();
        standard().logCheck();
        standard().logCheck();

        verify(llm, times(3)).listChatModels();
    }
}
