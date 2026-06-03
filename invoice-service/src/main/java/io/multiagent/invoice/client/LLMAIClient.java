package io.multiagent.invoice.client;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * OpenAI client wrapper for invoice-service.
 */
@Slf4j
@Component
public class LLMAIClient {

    private final OpenAIClient client;
    private final String embeddingModel;
    private final String llmModel;
    private final int maxAttempts;
    private final long baseBackoffMs;
    private final long maxBackoffMs;

    public LLMAIClient(
            @Value("${openai.api-key}") String apiKey,
            @Value("${openai.embedding-model:text-embedding-3-large}") String embeddingModel,
            @Value("${openai.model:gpt-4o-mini}") String llmModel,
            @Value("${openai.retry.max-attempts:4}") int maxAttempts,
            @Value("${openai.retry.base-backoff-ms:1500}") long baseBackoffMs,
            @Value("${openai.retry.max-backoff-ms:15000}") long maxBackoffMs) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("openai.api-key must be provided (set OPENAI_API_KEY)");
        }
        this.client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
        this.embeddingModel = embeddingModel;
        this.llmModel = llmModel;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseBackoffMs = Math.max(100, baseBackoffMs);
        this.maxBackoffMs = Math.max(this.baseBackoffMs, maxBackoffMs);
        log.info("invoice-service LLMAIClient initialized — model={}, embedding={}", llmModel, embeddingModel);
    }

    public List<Double> embed(String model, String text) {
        List<Float> vector = embedVector(model, text);
        return vector.stream().map(Float::doubleValue).collect(Collectors.toList());
    }

    public ChatCompletion chatJson(String model, String system, String user) {
        String targetModel = resolveModel(model, this.llmModel);
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(targetModel)
                .messages(List.of(
                        ChatCompletionMessageParam.ofSystem(
                                ChatCompletionSystemMessageParam.builder().content(system).build()
                        ),
                        ChatCompletionMessageParam.ofUser(
                                ChatCompletionUserMessageParam.builder().content(user).build()
                        )
                ))
                .responseFormat(ChatCompletionCreateParams.ResponseFormat.Companion.ofJsonObject(
                        ResponseFormatJsonObject.builder().build()
                ))
                .build();
        return safeCall("chat.json(model=" + targetModel + ")", () -> client.chat().completions().create(params));
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public String getLlmModel() {
        return llmModel;
    }

    private List<Float> embedVector(String model, String text) {
        String targetModel = resolveModel(model, this.embeddingModel);
        EmbeddingCreateParams params = EmbeddingCreateParams.builder()
                .model(targetModel)
                .input(text)
                .build();
        CreateEmbeddingResponse response = safeCall(
                "embeddings.single(model=" + targetModel + ")",
                () -> client.embeddings().create(params)
        );
        if (response.data().isEmpty()) {
            return List.of();
        }
        Embedding embedding = response.data().get(0);
        return new ArrayList<>(embedding.embedding());
    }

    private String resolveModel(String candidate, String fallback) {
        return (candidate == null || candidate.isBlank()) ? fallback : candidate;
    }

    private <T> T safeCall(String operation, Supplier<T> supplier) {
        int attempt = 1;
        while (true) {
            try {
                return supplier.get();
            } catch (Exception ex) {
                boolean is429 = ex.getMessage() != null && ex.getMessage().contains("429");
                if (!is429 || attempt >= maxAttempts) {
                    throw new RuntimeException("OpenAI call failed for operation=" + operation, ex);
                }
                long sleepMs = computeBackoff(attempt);
                log.warn("OpenAI rate-limited (op={} attempt {}/{}). Retry in {} ms: {}",
                        operation, attempt, maxAttempts, sleepMs, ex.getMessage());
                sleepQuietly(sleepMs);
                attempt++;
            }
        }
    }

    private long computeBackoff(int attempt) {
        double exp = Math.min(maxBackoffMs, baseBackoffMs * Math.pow(2, attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(100, 400);
        return Math.min(maxBackoffMs, (long) exp + jitter);
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
