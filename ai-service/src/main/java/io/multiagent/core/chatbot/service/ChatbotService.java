package io.multiagent.core.chatbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.multiagent.core.client.LLMAIClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Assistant commercial public du site vitrine (ia-insightservices.fr).
 *
 * Endpoint anonyme — protections spécifiques en plus du PromptGuardFilter :
 * rate-limit par IP et global, taille d'entrée plafonnée, sortie texte brut.
 */
@Slf4j
@Service
public class ChatbotService {

    private static final int MAX_MESSAGE_CHARS = 500;
    private static final int MAX_PER_MINUTE_PER_IP = 8;
    private static final int MAX_PER_MINUTE_GLOBAL = 60;
    private static final int MAX_REPLY_CHARS = 1200;



    private final LLMAIClient llm;
    private final ObjectMapper objectMapper;
    private final io.multiagent.core.i18n.Messages messages;
    private final String systemPrompt;
    /** Modèle 17B (celui du RAG) — le 8B par défaut ne respecte pas le cadrage anti-hors-sujet. */
    private final String chatbotModel;

    /** Compteurs de requêtes par IP sur la minute courante (clé spéciale "_global" pour le total). */
    private final Map<String, IpWindow> windows = new ConcurrentHashMap<>();

    public ChatbotService(LLMAIClient llm,
                          ObjectMapper objectMapper,
                          @Value("${AI_CORE_PROMPT_CHATBOT_SYSTEM:}") String configuredPrompt,
                          @Value("${AI_CORE_CHATBOT_MODEL:${AI_CORE_RAG_MODEL:meta-llama/llama-4-scout-17b-16e-instruct}}") String chatbotModel,
                          io.multiagent.core.i18n.Messages messages) {
        this.llm = llm;
        this.messages = messages;
        this.objectMapper = objectMapper;
        this.chatbotModel = chatbotModel;
        if (configuredPrompt == null || configuredPrompt.isBlank()) {
            log.warn("AI_CORE_PROMPT_CHATBOT_SYSTEM absent du ConfigMap — chatbot désactivé (réponse de secours uniquement)");
            this.systemPrompt = null;
        } else {
            this.systemPrompt = configuredPrompt;
        }
    }

    /** @return true si la requête est autorisée pour cette IP (fenêtre glissante d'une minute). */
    public boolean allow(String clientIp) {
        long minute = System.currentTimeMillis() / 60_000;
        // Purge paresseuse : retirer les fenêtres expirées pour éviter la croissance mémoire
        windows.entrySet().removeIf(e -> e.getValue().minute != minute);
        int global = windows.computeIfAbsent("_global", k -> new IpWindow(minute)).increment(minute);
        int perIp  = windows.computeIfAbsent(clientIp, k -> new IpWindow(minute)).increment(minute);
        return global <= MAX_PER_MINUTE_GLOBAL && perIp <= MAX_PER_MINUTE_PER_IP;
    }

    public String reply(String message) {
        String question = message.length() > MAX_MESSAGE_CHARS
                ? message.substring(0, MAX_MESSAGE_CHARS)
                : message;
        if (systemPrompt == null) return messages.get("chatbot.fallback");
        try {
            String json = llm.extractJSONWithModel(chatbotModel, systemPrompt, question);
            JsonNode node = objectMapper.readTree(json);
            String reply = node.hasNonNull("reply") ? node.get("reply").asText("") : "";
            if (reply.isBlank()) {
                return messages.get("chatbot.fallback");
            }
            return reply.length() > MAX_REPLY_CHARS ? reply.substring(0, MAX_REPLY_CHARS) : reply;
        } catch (Exception ex) {
            log.warn("[Chatbot] Échec LLM, réponse de secours renvoyée : {}", ex.getMessage());
            return messages.get("chatbot.fallback");
        }
    }

    private static final class IpWindow {
        volatile long minute;
        final AtomicInteger count = new AtomicInteger();

        IpWindow(long minute) {
            this.minute = minute;
        }

        int increment(long currentMinute) {
            if (minute != currentMinute) {
                synchronized (this) {
                    if (minute != currentMinute) {
                        minute = currentMinute;
                        count.set(0);
                    }
                }
            }
            return count.incrementAndGet();
        }
    }
}
