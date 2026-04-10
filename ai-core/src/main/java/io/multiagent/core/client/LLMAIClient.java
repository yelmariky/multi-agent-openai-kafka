package io.multiagent.core.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.errors.RateLimitException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.multiagent.core.exception.LLMClientException;
import io.multiagent.core.model.ReRankScore;
import io.multiagent.core.util.LLMUtils;
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
 * Centralized OpenAI client wrapper used by AI-Core services for chat, embeddings,
 * and reranking while recording metrics.
 *
 * Changes vs previous version:
 * - Removed legacy Completions API (completion() method) — not supported by gpt-4.x models
 * - Added maxTokens parameter (configurable, default 2048) to all chat calls
 * - Replaced JSON mode with Structured Outputs where a schema is provided
 * - Unified embed() to always return float[] (removed inconsistent List<Double> overload)
 * - Optimised rerank() to a single batch embedding call instead of two API calls
 * - Removed dead code (commented-out main() method)
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
    private final long defaultMaxTokens;

    public LLMAIClient(
            @Value("${openai.api-key}") String apiKey,
            @Value("${openai.embedding-model:text-embedding-3-large}") String embeddingModel,
            @Value("${openai.model:gpt-4.1-mini}") String llmModel,
            @Value("${openai.retry.max-attempts:4}") int maxAttempts,
            @Value("${openai.retry.base-backoff-ms:1500}") long baseBackoffMs,
            @Value("${openai.retry.max-backoff-ms:15000}") long maxBackoffMs,
            @Value("${openai.default-max-tokens:2048}") long defaultMaxTokens,
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
        this.defaultMaxTokens = Math.max(256, defaultMaxTokens);

        log.info("LLMClient initialized — model={}, embedding={}, maxTokens={}",
                llmModel, embeddingModel, defaultMaxTokens);
    }

    // -------------------------------------------------------------------------
    // Embeddings
    // -------------------------------------------------------------------------

    /** Embeds a single text using the configured default embedding model. */
    public float[] embed(String text) {
        return embedVector(this.embeddingModel, text);
    }

    /** Embeds a single text using a specific model. */
    public float[] embed(String model, String text) {
        return embedVector(model, text);
    }

    // -------------------------------------------------------------------------
    // Chat — JSON mode (fallback when no schema is available)
    // -------------------------------------------------------------------------

    /**
     * Calls Chat Completions in JSON mode (response_format: json_object).
     * Prefer {@link #chatStructured} when you have a JSON Schema — it is more reliable.
     */
    public ChatCompletion chatJson(String model, String system, String user) {
        return chatJson(model, system, user, defaultMaxTokens);
    }

    public ChatCompletion chatJson(String model, String system, String user, long maxTokens) {
        String targetModel = resolveModel(model, this.llmModel);

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(targetModel)
                .maxTokens(maxTokens)
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
                        com.openai.models.ResponseFormatJsonObject.builder().build()
                ))
                .build();

        return safeCall("chat.json(model=" + targetModel + ")",
                () -> client.chat().completions().create(params));
    }

    // -------------------------------------------------------------------------
    // Chat — Structured Outputs (OpenAI guarantees schema compliance)
    // -------------------------------------------------------------------------

    /**
     * Calls Chat Completions with Structured Outputs.
     * OpenAI will strictly conform to the provided JSON Schema — no parsing errors.
     *
     * @param schemaName  A unique name for the schema (e.g. "ExpenseItem")
     * @param schema      The JSON Schema as a JsonNode
     */
    public ChatCompletion chatStructured(String model, String system, String user,
                                         String schemaName, JsonNode schema) {
        return chatStructured(model, system, user, schemaName, schema, defaultMaxTokens);
    }

    public ChatCompletion chatStructured(String model, String system, String user,
                                          String schemaName, JsonNode schema, long maxTokens) {
        String targetModel = resolveModel(model, this.llmModel);

        ResponseFormatJsonSchema.JsonSchema jsonSchema = ResponseFormatJsonSchema.JsonSchema.builder()
                .name(schemaName)
                .schema(schema)
                .strict(true)
                .build();

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(targetModel)
                .maxTokens(maxTokens)
                .messages(List.of(
                        ChatCompletionMessageParam.ofSystem(
                                ChatCompletionSystemMessageParam.builder().content(system).build()
                        ),
                        ChatCompletionMessageParam.ofUser(
                                ChatCompletionUserMessageParam.builder().content(user).build()
                        )
                ))
                .responseFormat(ChatCompletionCreateParams.ResponseFormat.Companion.ofJsonSchema(
                        ResponseFormatJsonSchema.builder().jsonSchema(jsonSchema).build()
                ))
                .build();

        return safeCall("chat.structured(model=" + targetModel + ", schema=" + schemaName + ")",
                () -> client.chat().completions().create(params));
    }

    // -------------------------------------------------------------------------
    // Rerank — single batch embedding call (optimised: was 2 API calls)
    // -------------------------------------------------------------------------

    /**
     * Reranks documents against a query using cosine similarity on embeddings.
     * Query and all documents are sent in a single batch call to minimise latency.
     */
    public List<ReRankScore> rerank(String query, List<String> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        // Single batch: query at index 0, documents at indices 1..N
        List<String> allTexts = new ArrayList<>(documents.size() + 1);
        allTexts.add(query);
        allTexts.addAll(documents);

        List<float[]> allVectors = embedBatch(this.embeddingModel, allTexts);

        float[] queryVector = allVectors.get(0);
        List<ReRankScore> scored = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            double score = cosine(queryVector, allVectors.get(i + 1));
            scored.add(new ReRankScore(i, score));
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(ReRankScore::getScore).reversed())
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // High-level JSON extraction helpers
    // -------------------------------------------------------------------------

    public String extractJSON(String prompt) {
        ChatCompletion completion = chatJson(
                null,
                "Tu réponds uniquement en JSON strict sans texte supplémentaire.",
                prompt
        );
        return extractContentOrThrow(completion);
    }

    public String extractJSON(String systemPrompt, String userPrompt) {
        ChatCompletion completion = chatJson(null, systemPrompt, userPrompt);
        return extractContentOrThrow(completion);
    }

    public String extractJSON(String systemPrompt, String userPrompt, long maxTokens) {
        ChatCompletion completion = chatJson(null, systemPrompt, userPrompt, maxTokens);
        return extractContentOrThrow(completion);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String getLlmModel() {
        return llmModel;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private float[] embedVector(String model, String text) {
        String targetModel = resolveModel(model, this.embeddingModel);

        EmbeddingCreateParams params = EmbeddingCreateParams.builder()
                .model(targetModel)
                .input(text)
                .build();

        CreateEmbeddingResponse response = safeCall(
                "embeddings.single(model=" + targetModel + ")",
                () -> client.embeddings().create(params));

        if (response.data().isEmpty()) {
            return new float[0];
        }
        List<Float> raw = response.data().get(0).embedding();
        float[] array = new float[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            array[i] = raw.get(i);
        }
        return array;
    }

    private List<float[]> embedBatch(String model, List<String> texts) {
        String targetModel = resolveModel(model, this.embeddingModel);

        EmbeddingCreateParams params = EmbeddingCreateParams.builder()
                .model(targetModel)
                .inputOfArrayOfStrings(texts)
                .build();

        CreateEmbeddingResponse response = safeCall(
                "embeddings.batch(model=" + targetModel + ", n=" + texts.size() + ")",
                () -> client.embeddings().create(params));

        return response.data().stream()
                .map(Embedding::embedding)
                .map(list -> {
                    float[] arr = new float[list.size()];
                    for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
                    return arr;
                })
                .collect(Collectors.toList());
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0.0, na = 0.0, nb = 0.0;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            dot += (double) a[i] * b[i];
            na  += (double) a[i] * a[i];
            nb  += (double) b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-12);
    }

    private String extractContentOrThrow(ChatCompletion completion) {
        String content = LLMUtils.extractChatContent(completion).trim();
        if (content.isBlank()) {
            throw new LLMClientException("LLM returned an empty JSON payload");
        }
        return content;
    }

    private String resolveModel(String candidate, String fallback) {
        return (candidate == null || candidate.isBlank()) ? fallback : candidate;
    }

    private <T> T record(String metricSuffix, Supplier<T> supplier) {
        Timer.Sample sample = Timer.start(metrics);
        try {
            return supplier.get();
        } finally {
            sample.stop(metrics.timer("openai." + metricSuffix));
        }
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
