package io.multiagent.reassign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

@Slf4j
@Service
public class Agent {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final WebClient aiCoreClient;
    private final String auditTopic;
    private final ObjectMapper objectMapper;
    private final Duration aiCoreTimeout;

    public Agent(
            KafkaTemplate<String, String> kafkaTemplate,
            WebClient.Builder webClientBuilder,
            @Value("${AI_CORE_URL:http://ai-core:8081}") String aiCoreUrl,
            @Value("${REASSIGN_OUTPUT_TOPIC:audit.events.in}") String auditTopic,
            @Value("${AI_CORE_TIMEOUT:15s}") Duration aiCoreTimeout,
            ObjectMapper objectMapper
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.aiCoreClient = webClientBuilder.baseUrl(aiCoreUrl).build();
        this.auditTopic = auditTopic;
        this.objectMapper = objectMapper;
        this.aiCoreTimeout = aiCoreTimeout;
    }

    @KafkaListener(topics = "${REASSIGN_INPUT_TOPIC:reassign-input-topic}", groupId = "reassign-agent")
    public void processMessage(String reasoningResultJson) {
        log.info("Reassign-Agent received message");
        log.debug("Reassign-Agent payload: {}", reasoningResultJson);
        try {
            JsonNode root = objectMapper.readTree(reasoningResultJson);
            String type = root.path("type").asText();

            if ("generate_invoice".equals(type)) {
                handleInvoiceGeneration(root);
            } else {
                log.info("Reassign-Agent: type '{}' not handled for action, forwarding to audit.", type);
                kafkaTemplate.send(auditTopic, reasoningResultJson);
            }
        } catch (Exception e) {
            log.error("Reassign-Agent: Failed to process message", e);
            try {
                // Sérialisation sûre via Jackson pour éviter toute injection JSON
                String errorJson = objectMapper.writeValueAsString(Map.of(
                        "error", e.getMessage() != null ? e.getMessage() : "unknown error",
                        "originalMessage", objectMapper.readTree(reasoningResultJson)
                ));
                kafkaTemplate.send(auditTopic, errorJson);
            } catch (Exception serializationError) {
                log.error("Reassign-Agent: Failed to serialize error message", serializationError);
            }
        }
    }

    private void handleInvoiceGeneration(JsonNode reasoningResult) {
        JsonNode metadata = reasoningResult.path("metadata");
        if (!metadata.has("invoiceDataJson")) {
            log.error("Reassign-Agent: 'invoiceDataJson' missing in metadata for generate_invoice.");
            return;
        }

        String invoiceRequestJson = metadata.get("invoiceDataJson").asText();
        log.info("Reassign-Agent: Triggering PDF generation");
        log.debug("Reassign-Agent: invoice data: {}", invoiceRequestJson);

        aiCoreClient.post()
                .uri("/invoices/generate")
                .header("Content-Type", "application/json")
                .bodyValue(invoiceRequestJson)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(aiCoreTimeout)
                .doOnSuccess(response -> {
                    log.info("Reassign-Agent: Invoice generation successful, forwarding to audit.");
                    kafkaTemplate.send(auditTopic, reasoningResult.toString());
                })
                .doOnError(error -> log.error("Reassign-Agent: Error calling AI-Core for invoice generation", error))
                .subscribe();
    }
}
