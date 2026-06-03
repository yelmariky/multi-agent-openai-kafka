package io.multiagent.core.invoice.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP client for invoice-service.
 * Used by ReasoningService to delegate delete_invoice to the dedicated microservice.
 * Best-effort: never throws — logs WARN on error and returns an empty map.
 */
@Slf4j
@Service
public class InvoiceClient {

    private final WebClient webClient;

    public InvoiceClient(
            @Value("${invoice.service.url:http://invoice-service:8083}") String baseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * POST /invoices/delete-by-text
     * @param text natural language text describing which invoice to delete
     * @return Map with keys: deleted (int), invoiceName, billingMonth, sellerCompanyName, error (if any)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> deleteByText(String text) {
        try {
            Map<String, Object> result = webClient.post()
                    .uri("/invoices/delete-by-text")
                    .bodyValue(Map.of("text", text))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
            return result != null ? (Map<String, Object>) result : new HashMap<>();
        } catch (Exception e) {
            log.warn("InvoiceClient.deleteByText failed (best-effort): {}", e.getMessage());
            return new HashMap<>();
        }
    }
}
