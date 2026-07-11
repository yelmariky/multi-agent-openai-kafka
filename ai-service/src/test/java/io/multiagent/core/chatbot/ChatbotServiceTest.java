package io.multiagent.core.chatbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.multiagent.core.chatbot.service.ChatbotService;
import io.multiagent.core.client.LLMAIClient;
import io.multiagent.core.exception.LLMClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatbotService — assistant public du site vitrine")
class ChatbotServiceTest {

    @Mock LLMAIClient llm;

    private ChatbotService service;

    @BeforeEach
    void setUp() {
        service = new ChatbotService(llm, new ObjectMapper(), "", "test-model");
    }

    @Test
    @DisplayName("réponse LLM JSON valide → texte de la clé reply")
    void replyReturnsLlmAnswer() {
        when(llm.extractJSONWithModel(eq("test-model"), anyString(), anyString()))
                .thenReturn("{\"reply\":\"Nos offres démarrent à 29€.\"}");

        assertThat(service.reply("Quels tarifs ?")).isEqualTo("Nos offres démarrent à 29€.");
    }

    @Test
    @DisplayName("prompt configMap absent → prompt par défaut embarqué (contient les faits IA-INSIGHT)")
    void blankPromptFallsBackToDefault() {
        when(llm.extractJSONWithModel(anyString(), anyString(), anyString()))
                .thenReturn("{\"reply\":\"ok\"}");
        service.reply("tarifs ?");

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(llm).extractJSONWithModel(eq("test-model"), system.capture(), anyString());
        assertThat(system.getValue()).contains("IA-INSIGHT").contains("29€").contains("demo@ia-insightservices.fr");
    }

    @Test
    @DisplayName("prompt configMap fourni → utilisé tel quel")
    void configuredPromptIsUsed() {
        ChatbotService custom = new ChatbotService(llm, new ObjectMapper(), "PROMPT CUSTOM", "m");
        when(llm.extractJSONWithModel(anyString(), anyString(), anyString()))
                .thenReturn("{\"reply\":\"ok\"}");
        custom.reply("question");
        verify(llm).extractJSONWithModel(eq("m"), eq("PROMPT CUSTOM"), anyString());
    }

    @Test
    @DisplayName("échec LLM → réponse de secours avec les coordonnées, jamais d'exception")
    void llmFailureReturnsFallback() {
        when(llm.extractJSONWithModel(anyString(), anyString(), anyString()))
                .thenThrow(new LLMClientException("Groq down"));

        String reply = service.reply("tarifs ?");
        assertThat(reply).contains("demo@ia-insightservices.fr").contains("06 69 00 91 08");
    }

    @Test
    @DisplayName("JSON sans clé reply ou reply vide → réponse de secours")
    void emptyReplyReturnsFallback() {
        when(llm.extractJSONWithModel(anyString(), anyString(), anyString()))
                .thenReturn("{\"autre\":\"x\"}");
        assertThat(service.reply("q")).contains("06 69 00 91 08");

        when(llm.extractJSONWithModel(anyString(), anyString(), anyString()))
                .thenReturn("{\"reply\":\"\"}");
        assertThat(service.reply("q")).contains("06 69 00 91 08");
    }

    @Test
    @DisplayName("réponse LLM trop longue → tronquée à 1200 caractères (OWASP LLM04)")
    void oversizedReplyIsTruncated() {
        String big = "x".repeat(5000);
        when(llm.extractJSONWithModel(anyString(), anyString(), anyString()))
                .thenReturn("{\"reply\":\"" + big + "\"}");

        assertThat(service.reply("q")).hasSize(1200);
    }

    @Test
    @DisplayName("message utilisateur > 500 caractères → tronqué avant l'appel LLM")
    void oversizedMessageIsTruncatedBeforeLlm() {
        when(llm.extractJSONWithModel(anyString(), anyString(), anyString()))
                .thenReturn("{\"reply\":\"ok\"}");
        service.reply("m".repeat(2000));

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(llm).extractJSONWithModel(anyString(), anyString(), user.capture());
        assertThat(user.getValue()).hasSize(500);
    }

    @Test
    @DisplayName("rate-limit : 8 requêtes/minute par IP, la 9e est refusée")
    void perIpRateLimitBlocksNinthRequest() {
        for (int i = 0; i < 8; i++) {
            assertThat(service.allow("1.2.3.4")).as("requête %d", i + 1).isTrue();
        }
        assertThat(service.allow("1.2.3.4")).isFalse();
    }

    @Test
    @DisplayName("rate-limit : IPs distinctes comptées séparément sous le plafond global")
    void distinctIpsHaveIndependentBuckets() {
        for (int i = 0; i < 8; i++) service.allow("10.0.0.1");
        assertThat(service.allow("10.0.0.1")).isFalse();
        assertThat(service.allow("10.0.0.2")).isTrue();
    }

    @Test
    @DisplayName("rate-limit global : 60 requêtes/minute toutes IPs confondues")
    void globalRateLimitCapsTotalTraffic() {
        // 8 IPs différentes x 8 requêtes = 64 tentatives — le plafond global (60) doit couper avant
        int allowed = 0;
        for (int ip = 0; ip < 8; ip++) {
            for (int i = 0; i < 8; i++) {
                if (service.allow("192.168.0." + ip)) allowed++;
            }
        }
        assertThat(allowed).isLessThanOrEqualTo(60);
    }
}
