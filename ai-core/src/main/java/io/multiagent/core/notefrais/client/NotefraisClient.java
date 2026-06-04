package io.multiagent.core.notefrais.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.multiagent.core.model.IntentResult;
import io.multiagent.core.model.ReasoningResult;
import io.multiagent.core.model.ExpenseItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for note-frais-service.
 * Delegates all expense-related reasoning (create, report, delete) to the dedicated microservice.
 * Best-effort: never throws — logs WARN on error and returns an error ReasoningResult.
 */
@Slf4j
@Service
public class NotefraisClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public NotefraisClient(
            @Value("${notefrais.service.url:http://note-frais-service:8082}") String baseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * POST /reasoning/process
     * Delegates expense intent processing to note-frais-service.
     *
     * @param text           original user text
     * @param intent         classified intent (serialized as JSON in the request)
     * @param consultantEmail optional consultant email
     * @return ReasoningResult from note-frais-service
     */
    public ReasoningResult process(String text, IntentResult intent, String consultantEmail) {
        try {
            String intentJson = objectMapper.writeValueAsString(intent);
            Map<String, Object> body = Map.of(
                    "text", text != null ? text : "",
                    "intentJson", intentJson,
                    "consultantEmail", consultantEmail != null ? consultantEmail : ""
            );

            ReasoningResult result = webClient.post()
                    .uri("/reasoning/process")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(ReasoningResult.class)
                    .timeout(Duration.ofSeconds(60))
                    .block();

            return result != null ? result : ReasoningResult.error("note-frais-service returned null");
        } catch (Exception e) {
            log.warn("NotefraisClient.process failed (best-effort): {}", e.getMessage());
            return ReasoningResult.error("note-frais-service unavailable: " + e.getMessage());
        }
    }
}
