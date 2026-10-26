package io.multiagent.core.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Vérifie que les modèles LLM configurés existent toujours chez le provider (Groq retire
 * régulièrement des modèles). Un modèle retiré ne casse rien de visible — {@link LLMAIClient}
 * bascule silencieusement sur OpenAI, payant — d'où ce contrôle : composant {@code llm} DOWN dans
 * {@code /actuator/health} et log ERROR au démarrage. N'affecte pas les probes liveness/readiness
 * (groupes dédiés) : un modèle retiré ne redémarre jamais le pod.
 */
@Slf4j
@Component("llmHealthIndicator")
public class LlmModelsHealthIndicator implements HealthIndicator {

    // /actuator/health ne doit pas appeler le provider à chaque requête.
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final LLMAIClient llm;
    private final Set<String> configuredModels;
    private volatile Health cached;
    private volatile Instant cachedAt;

    public LlmModelsHealthIndicator(
            LLMAIClient llm,
            @Value("${openai.model:}") String defaultModel,
            @Value("${ai-core.openai.intent-model:}") String intentModel,
            @Value("${ai-core.openai.rewrite-model:}") String rewriteModel,
            @Value("${ai-core.openai.rag-model:}") String ragModel,
            @Value("${OPENAI_EXPENSE_MODEL:openai/gpt-oss-120b}") String expenseModel,
            @Value("${AI_CORE_CHATBOT_MODEL:}") String chatbotModel) {
        this.llm = llm;
        this.configuredModels = Stream.of(defaultModel, intentModel, rewriteModel, ragModel, expenseModel, chatbotModel)
                .filter(m -> m != null && !m.isBlank())
                .map(String::strip)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Override
    public Health health() {
        Health current = cached;
        if (current != null && Instant.now().isBefore(cachedAt.plus(CACHE_TTL))) return current;
        current = check();
        cached = current;
        cachedAt = Instant.now();
        return current;
    }

    private Health check() {
        if (!llm.usesAlternateProvider()) {
            // OpenAI seul : pas de provider alternatif dont les modèles pourraient disparaître.
            return Health.up().withDetail("provider", "openai").withDetail("models", configuredModels).build();
        }
        try {
            Set<String> available = llm.listChatModels();
            List<String> missing = configuredModels.stream().filter(m -> !available.contains(m)).toList();
            if (missing.isEmpty()) {
                return Health.up().withDetail("provider", "alternate").withDetail("models", configuredModels).build();
            }
            return Health.down()
                    .withDetail("missing", missing)
                    .withDetail("hint", "model removed by the provider — update the ConfigMap")
                    .build();
        } catch (Exception e) {
            // Panne réseau / clé invalide : ne pas la confondre avec un modèle retiré.
            return Health.unknown().withDetail("error", String.valueOf(e.getMessage())).build();
        }
    }

    /** Contrôle au démarrage, hors thread principal (appel réseau : ne doit pas retarder le démarrage). */
    @EventListener(ApplicationReadyEvent.class)
    public void checkAtStartup() {
        Thread.ofVirtual().name("llm-models-check").start(this::logCheck);
    }

    void logCheck() {
        Health h = health();
        if (Status.DOWN.equals(h.getStatus())) {
            log.error("❌ [LLM] Modèles introuvables chez le provider : {} — bascule OpenAI à chaque appel",
                    h.getDetails().get("missing"));
        } else if (Status.UNKNOWN.equals(h.getStatus())) {
            log.warn("⚠️ [LLM] Vérification des modèles impossible : {}", h.getDetails().get("error"));
        } else {
            log.info("✅ [LLM] Modèles vérifiés chez le provider : {}", configuredModels);
        }
    }
}
