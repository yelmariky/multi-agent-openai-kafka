package io.multiagent.notefrais.client;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import com.openai.models.completions.Completion;
import com.openai.models.completions.CompletionCreateParams;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.errors.RateLimitException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.multiagent.notefrais.exception.LLMClientException;
import io.multiagent.notefrais.model.ReRankScore;
import io.multiagent.notefrais.util.LLMUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Centralized OpenAI client wrapper for note-frais-service.
 */
@Slf4j
@Component
public class LLMAIClient {

    private final OpenAIClient client;
    private final String embeddingModel;
    private final String llmModel;
    private final MeterRegistry metrics;
    private final int maxAttempts;
    private final long baseBackoffMs;
    private final long maxBackoffMs;

    public LLMAIClient(
            @Value("${openai.api-key}") String apiKey,
            @Value("${openai.embedding-model:text-embedding-3-large}") String embeddingModel,
            @Value("${openai.model:gpt-4.1-mini}") String llmModel,
            @Value("${openai.retry.max-attempts:4}") int maxAttempts,
            @Value("${openai.retry.base-backoff-ms:1500}") long baseBackoffMs,
            @Value("${openai.retry.max-backoff-ms:15000}") long maxBackoffMs,
            MeterRegistry registry) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("openai.api-key must be provided (set OPENAI_API_KEY)");
        }

        this.client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .build();

        this.embeddingModel = embeddingModel;
        this.llmModel = llmModel;
        this.metrics = registry;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseBackoffMs = Math.max(100, baseBackoffMs);
        this.maxBackoffMs = Math.max(this.baseBackoffMs, maxBackoffMs);

        log.info("LLMClient initialized — model={}, embedding={}", llmModel, embeddingModel);
    }

    public float[] embed(String text) {
        List<Float> vector = embedVector(this.embeddingModel, text);
        float[] array = new float[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            array[i] = vector.get(i);
        }
        return array;
    }

    public List<Double> embed(String model, String text) {
        List<Float> vector = embedVector(model, text);
        return vector.stream()
                .map(Float::doubleValue)
                .collect(Collectors.toList());
    }

    public ChatCompletion chatJson(String model, String system, String user) {
        String targetModel = resolveModel(model, this.llmModel);

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(targetModel)
                .messages(List.of(
                        ChatCompletionMessageParam.ofSystem(
                                ChatCompletionSystemMessageParam.builder()
                                        .content(system)
                                        .build()
                        ),
                        ChatCompletionMessageParam.ofUser(
                                ChatCompletionUserMessageParam.builder()
                                        .content(user)
                                        .build()
                        )
                ))
                .responseFormat(ChatCompletionCreateParams.ResponseFormat.Companion.ofJsonObject(
                        ResponseFormatJsonObject.builder().build()
                ))
                .build();

        return safeCall("chat.json(model=" + targetModel + ")", () -> client.chat().completions().create(params));
    }

    public String completion(String prompt) {
        CompletionCreateParams params = CompletionCreateParams.builder()
                .model(llmModel)
                .prompt(prompt)
                .maxTokens(200L)
                .build();

        Completion res = safeCall("completion(model=" + llmModel + ")", () -> client.completions().create(params));
        if (res.choices().isEmpty()) {
            return "";
        }
        return res.choices().get(0).text();
    }

    public List<ReRankScore> rerank(String query, List<String> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        List<Float> queryVector = embedVector(this.embeddingModel, query);
        List<List<Float>> docVectors = embedBatch(this.embeddingModel, documents);

        List<ReRankScore> scored = new ArrayList<>();
        int limit = Math.min(docVectors.size(), documents.size());
        for (int i = 0; i < limit; i++) {
            List<Float> dVec = docVectors.get(i);
            double score = cosine(queryVector, dVec);
            scored.add(new ReRankScore(i, score));
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(ReRankScore::getScore).reversed())
                .collect(Collectors.toList());
    }

    private static double cosine(List<Float> a, List<Float> b) {
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.size(); i++) {
            double x = a.get(i), y = b.get(i);
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-12);
    }

    private List<Float> embedVector(String model, String text) {
        String targetModel = resolveModel(model, this.embeddingModel);

        EmbeddingCreateParams params = EmbeddingCreateParams.builder()
                .model(targetModel)
                .input(text)
                .build();

        CreateEmbeddingResponse response = safeCall("embeddings.single(model=" + targetModel + ")", () -> client.embeddings().create(params));
        if (response.data().isEmpty()) {
            return List.of();
        }
        Embedding embedding = response.data().get(0);
        return new ArrayList<>(embedding.embedding());
    }

    private List<List<Float>> embedBatch(String model, List<String> documents) {
        String targetModel = resolveModel(model, this.embeddingModel);

        EmbeddingCreateParams params = EmbeddingCreateParams.builder()
                .model(targetModel)
                .inputOfArrayOfStrings(documents)
                .build();

        CreateEmbeddingResponse response = safeCall("embeddings.batch(model=" + targetModel + ")", () -> client.embeddings().create(params));
        return response.data().stream()
                .map(Embedding::embedding)
                .map(ArrayList::new)
                .collect(Collectors.toList());
    }

    private String resolveModel(String candidate, String fallback) {
        return (candidate == null || candidate.isBlank()) ? fallback : candidate;
    }

    private <T> T record(String metricSuffix, Supplier<T> supplier) {
        if (metrics == null) {
            return supplier.get();
        }

        Timer.Sample sample = Timer.start(metrics);
        try {
            return supplier.get();
        } finally {
            sample.stop(metrics.timer("openai." + metricSuffix));
        }
    }

    public String extractJSON(String prompt) {
        ChatCompletion completion = chatJson(
                null,
                "Tu réponds uniquement en JSON strict sans texte supplémentaire.",
                prompt
        );
        return extractContentOrThrow(completion);
    }

    public String extractJSON(String systemPrompt, String userPrompt) {
        ChatCompletion completion = chatJson(
                null,
                systemPrompt,
                userPrompt
        );
        return extractContentOrThrow(completion);
    }

    private String extractContentOrThrow(ChatCompletion completion) {
        String content = LLMUtils.extractChatContent(completion).trim();
        if (content.isBlank()) {
            throw new LLMClientException("LLM returned an empty JSON payload");
        }
        return content;
    }

    public String getLlmModel() {
        return llmModel;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    private <T> T safeCall(String operation, Supplier<T> supplier) {
        int attempt = 1;
        while (true) {
            try {
                return record(operation, supplier);
            } catch (Exception ex) {
                boolean retryable = isRateLimited(ex) || is429(ex);
                if (!retryable || attempt >= maxAttempts) {
                    throw new LLMClientException("OpenAI call failed for operation=" + operation, ex);
                }
                long sleepMs = computeBackoff(attempt);
                log.warn("OpenAI rate-limited (op={} attempt {}/{}). Retry in {} ms: {}",
                        operation, attempt, maxAttempts, sleepMs, ex.getMessage());
                sleepQuietly(sleepMs);
                attempt++;
            }
        }
    }

    private boolean isRateLimited(Throwable ex) {
        return findCause(ex, RateLimitException.class) != null;
    }

    private boolean is429(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause.getMessage() != null && cause.getMessage().contains("429")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
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

    private <T extends Throwable> T findCause(Throwable ex, Class<T> clazz) {
        Throwable current = ex;
        while (current != null) {
            if (clazz.isInstance(current)) {
                return clazz.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
