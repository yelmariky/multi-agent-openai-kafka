package io.multiagent.core.client;

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
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.openai.errors.RateLimitException;
import io.multiagent.core.exception.LLMClientException;
import io.multiagent.core.model.ReRankScore;
import io.multiagent.core.util.LLMUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Centralized OpenAI client wrapper used by AI-Core services for chat, completion,
 * embeddings, and reranking while recording metrics.
 */
@Slf4j
@Component
public class LLMAIClient {

    private final OpenAIClient client;
    private final String embeddingModel;
    private final String llmModel;
    private final long embeddingDimensions;
    private final MeterRegistry metrics;
    private final int maxAttempts;
    private final long baseBackoffMs;
    private final long maxBackoffMs;

    // Client dédié aux appels LLM (chat/completion) — peut pointer vers Groq, Together.ai, ou OpenAI
    private final OpenAIClient chatClient;
    // true si chatClient != client (Groq/autre provider configuré) → fallback OpenAI disponible
    private final boolean hasFallback;
    private final String fallbackLlmModel;

    /**
     * Circuit breaker sur le provider LLM principal (Groq).
     * Évite de payer 4 tentatives × backoff exponentiel (~22s) à chaque appel
     * quand Groq est en panne prolongée — bascule directement sur OpenAI fallback
     * une fois le circuit ouvert, jusqu'à la fenêtre de demi-ouverture.
     */
    private final CircuitBreaker chatCircuitBreaker;

    public LLMAIClient(
            @Value("${openai.api-key}") String apiKey,
            @Value("${openai.llm.api-key:}") String llmApiKey,
            @Value("${openai.llm.base-url:}") String llmBaseUrl,
            @Value("${openai.embedding-model:text-embedding-3-large}") String embeddingModel,
            @Value("${openai.model:gpt-4.1-mini}") String llmModel,
            @Value("${openai.fallback-model:gpt-4.1-mini}") String fallbackLlmModel,
            @Value("${openai.embedding-dimensions:2000}") long embeddingDimensions,
            @Value("${openai.retry.max-attempts:4}") int maxAttempts,
            @Value("${openai.retry.base-backoff-ms:1500}") long baseBackoffMs,
            @Value("${openai.retry.max-backoff-ms:15000}") long maxBackoffMs,
            MeterRegistry registry) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("openai.api-key must be provided (set OPENAI_API_KEY)");
        }

        // Client embeddings — toujours OpenAI (Groq/Together.ai ne supportent pas les embeddings)
        this.client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .build();

        // Client LLM — Groq / Together.ai / OpenAI selon config
        if (llmBaseUrl != null && !llmBaseUrl.isBlank()) {
            String resolvedKey = (llmApiKey != null && !llmApiKey.isBlank()) ? llmApiKey : apiKey;
            this.chatClient = OpenAIOkHttpClient.builder()
                    .apiKey(resolvedKey)
                    .baseUrl(llmBaseUrl)
                    .build();
            this.hasFallback = true;
            log.info("LLMClient — chatClient pointe vers: {} (fallback OpenAI: {})", llmBaseUrl, fallbackLlmModel);
        } else {
            this.chatClient = this.client;
            this.hasFallback = false;
        }

        this.embeddingModel = embeddingModel;
        this.llmModel = llmModel;
        this.fallbackLlmModel = fallbackLlmModel;
        this.embeddingDimensions = embeddingDimensions;
        this.metrics = registry;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseBackoffMs = Math.max(100, baseBackoffMs);
        this.maxBackoffMs = Math.max(this.baseBackoffMs, maxBackoffMs);

        // Circuit breaker Groq : ouvre après 50% d'échecs sur 5 appels min,
        // reste ouvert 30s avant de retester (half-open avec 2 appels d'essai).
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .minimumNumberOfCalls(5)
                .slidingWindowSize(10)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build();
        this.chatCircuitBreaker = CircuitBreaker.of("groq-chat", cbConfig);
        this.chatCircuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("🔌 [CircuitBreaker groq-chat] {} → {}",
                        event.getStateTransition().getFromState(), event.getStateTransition().getToState()));

        log.info("LLMClient initialized — model={}, embedding={}", llmModel, embeddingModel);
    }

    // 🔹 Embedding
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
        ChatCompletionCreateParams params = buildChatParams(targetModel, system, user);

        if (!hasFallback) {
            // Un seul provider configuré (pas de Groq) — pas de circuit breaker nécessaire
            return safeCall("chat.json(model=" + targetModel + ")", () -> chatClient.chat().completions().create(params));
        }

        try {
            // Circuit breaker autour du provider principal (Groq) + ses 4 tentatives internes
            return chatCircuitBreaker.executeSupplier(() ->
                    safeCall("chat.json(model=" + targetModel + ")", () -> chatClient.chat().completions().create(params)));
        } catch (CallNotPermittedException cnpe) {
            // Circuit ouvert : Groq court-circuité sans retry — bascule immédiate (économise ~22s)
            log.warn("🔌 [CircuitBreaker OUVERT] Groq court-circuité, bascule directe sur OpenAI fallback model={}",
                    fallbackLlmModel);
            ChatCompletionCreateParams fallbackParams = buildChatParams(fallbackLlmModel, system, user);
            return safeCall("chat.json.fallback(model=" + fallbackLlmModel + ")",
                    () -> client.chat().completions().create(fallbackParams));
        } catch (LLMClientException ex) {
            log.warn("⚠️ LLM provider indisponible ({}), bascule sur OpenAI fallback model={}: {}",
                    targetModel, fallbackLlmModel, ex.getMessage());
            ChatCompletionCreateParams fallbackParams = buildChatParams(fallbackLlmModel, system, user);
            return safeCall("chat.json.fallback(model=" + fallbackLlmModel + ")",
                    () -> client.chat().completions().create(fallbackParams));
        }
    }

    private ChatCompletionCreateParams buildChatParams(String model, String system, String user) {
        return ChatCompletionCreateParams.builder()
                .model(model)
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
                .maxTokens(4096L)   // OWASP LLM04 : limiter la taille de la sortie LLM
                .build();
    }

    // 🔹 Completion (texte brut)
    public String completion(String prompt) {
        CompletionCreateParams params = CompletionCreateParams.builder()
                .model(llmModel)
                .prompt(prompt)
                .maxTokens(200L)
                .build();

        Completion res = safeCall("completion(model=" + llmModel + ")", () -> chatClient.completions().create(params));
        if (res.choices().isEmpty()) {
            return "";
        }
        return res.choices().get(0).text();
    }

    // 🔹 Rerank par embeddings (similarité cosinus)
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

    // Fonction utilitaire pour la similarité cosinus
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
                .dimensions(embeddingDimensions)
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
                .dimensions(embeddingDimensions)
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
                null,          // utilise le modèle par défaut (llmModel)
                systemPrompt,  // system
                userPrompt     // user
        );
        return extractContentOrThrow(completion);
    }

    /** Extraction JSON avec un modèle explicitement spécifié (ex: llama-3.3-70b-versatile pour les tâches complexes). */
    public String extractJSONWithModel(String model, String systemPrompt, String userPrompt) {
        ChatCompletion completion = chatJson(model, systemPrompt, userPrompt);
        return extractContentOrThrow(completion);
    }

    /** Taille maximale acceptée pour une réponse LLM (OWASP LLM04 — DoS via sortie illimitée). */
    private static final int MAX_OUTPUT_CHARS = 32_000;

    private String extractContentOrThrow(ChatCompletion completion) {
        String content = LLMUtils.extractChatContent(completion).trim();
        if (content.isBlank()) {
            throw new LLMClientException("LLM returned an empty JSON payload");
        }
        // OWASP LLM04 : tronquer les réponses excessivement longues pour éviter le parsing OOM
        if (content.length() > MAX_OUTPUT_CHARS) {
            log.warn("⚠️ [LLM] Réponse tronquée : {} → {} chars (limite sécurité)", content.length(), MAX_OUTPUT_CHARS);
            content = content.substring(0, MAX_OUTPUT_CHARS);
        }
        // Retirer les caractères de contrôle dangereux de la sortie LLM
        content = content.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F﻿​]", "");
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
                log.warn("⏳ OpenAI rate-limited (op={} attempt {}/{}). Retry in {} ms: {}",
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
/** 
    // Démo
    public static void main(String[] args) {
        LLMAIClient client = new LLMAIClient(System.getenv("OPENAI_API_KEY"));

        // Embedding
        System.out.println("Embedding size: " + client.embed("Bonjour").size());

        // Chat JSON
        System.out.println("Chat JSON: " + client.chatJson("Donne un objet JSON avec 'framework'='Spring Boot'"));

        // Completion
        System.out.println("Completion: " + client.completion("Écris une phrase inspirante sur l’IA"));

        // Rerank
        List<String> docs = List.of(
                "Spring Boot est un framework pour créer des applications web.",
                "TensorFlow Java API permet de faire du machine learning.",
                "Hibernate est un ORM pour Java.",
                "Deeplearning4j est une bibliothèque Java pour l’IA.");
        client.rerank("Quels sont les frameworks Java utiles pour l’IA ?", docs)
                .forEach(s -> System.out.println(s.doc() + " | score=" + String.format("%.3f", s.score())));
    }
                **/
}
