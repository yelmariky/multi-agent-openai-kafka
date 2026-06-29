package io.multiagent.expense.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.multiagent.expense.client.LLMAIClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Garde de périmètre sémantique basée sur le LLM.
 *
 * <p>Contrairement aux patterns regex de {@link PromptGuard} (qui couvrent les attaques
 * structurelles universelles comme l'injection de rôle), ce service délègue AU LLM
 * la détection des opérations hors-périmètre : suppressions, gestion de factures,
 * requêtes admin — dans n'importe quelle langue (FR, EN, ES, AR, DE, ZH…).
 *
 * <p>Le prompt de sécurité est configurable via la variable d'env {@code AI_CORE_PROMPT_GUARD_SECURITY}
 * (ConfigMap K8s) — sans redéploiement du code.
 *
 * <p>Comportement de tolérance aux pannes :
 * <ul>
 *   <li>Si le LLM est indisponible → <b>fail open</b> (la requête passe, log WARN)</li>
 *   <li>Si le prompt n'est pas configuré → <b>fail open</b> (mode dégradé accepté)</li>
 *   <li>Les résultats sont mis en cache 5 minutes pour éviter les appels LLM répétés</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScopeGuardService {

    private final LLMAIClient llmClient;
    private final ObjectMapper objectMapper;

    @Value("${AI_CORE_PROMPT_GUARD_SECURITY:}")
    private String securityPromptEnv;

    /**
     * Comportement en cas d'erreur LLM (panne Groq/OpenAI, quota, timeout).
     * false (défaut) = fail-closed : le texte est BLOQUÉ — plus sûr.
     * true = fail-open : le texte est autorisé — plus disponible, moins sûr.
     * Configurable via ai-core.guard.scope-fail-open=true si nécessaire.
     */
    @Value("${ai-core.guard.scope-fail-open:false}")
    private boolean scopeFailOpen;

    private String securityPrompt;

    // Cache : hash du texte → safe (true/false), TTL 5 min
    private final Cache<Integer, Boolean> resultCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(500)
            .build();

    @PostConstruct
    public void init() {
        if (securityPromptEnv != null && !securityPromptEnv.isBlank()) {
            securityPrompt = securityPromptEnv.trim();
            log.info("🛡️ [SCOPE GUARD] Prompt de sécurité chargé depuis AI_CORE_PROMPT_GUARD_SECURITY ({} chars)",
                    securityPrompt.length());
        } else {
            securityPrompt = null;
            log.warn("🛡️ [SCOPE GUARD] AI_CORE_PROMPT_GUARD_SECURITY non configuré — détection LLM désactivée (mode dégradé)");
        }
    }

    /**
     * Analyse sémantiquement le texte et retourne {@code true} si le texte est
     * une note de frais légitime, {@code false} s'il est hors-périmètre ou suspect.
     *
     * <p>Fail open en cas d'erreur LLM ou de prompt manquant.
     *
     * @param text le texte brut soumis par le consultant
     * @return true = autorisé, false = bloquer
     */
    public boolean isSafe(String text) {
        if (text == null || text.isBlank()) return true;
        if (securityPrompt == null)          return true; // mode dégradé — pas de blocage

        // Cache hit
        int cacheKey = text.hashCode();
        Boolean cached = resultCache.getIfPresent(cacheKey);
        if (cached != null) {
            log.debug("🛡️ [SCOPE GUARD] Cache hit (safe={})", cached);
            return cached;
        }

        try {
            String response = llmClient.extractJSON(securityPrompt, text);
            JsonNode node   = objectMapper.readTree(response);
            boolean safe    = node.path("safe").asBoolean(true);
            String  reason  = node.path("reason").asText("—");

            if (!safe) {
                log.warn("🛡️ [SCOPE GUARD LLM] Texte rejeté — reason: {}", reason);
            } else {
                log.debug("🛡️ [SCOPE GUARD LLM] Texte autorisé — reason: {}", reason);
            }

            resultCache.put(cacheKey, safe);
            return safe;

        } catch (Exception e) {
            if (scopeFailOpen) {
                // Mode dégradé explicitement activé — fail-open (disponibilité > sécurité)
                log.warn("🛡️ [SCOPE GUARD LLM] Erreur LLM ({}) — fail-open activé, texte autorisé",
                        e.getMessage());
                return true;
            } else {
                // fail-closed par défaut (OWASP LLM01) — bloquer en cas de doute
                log.warn("🛡️ [SCOPE GUARD LLM] Erreur LLM ({}) — fail-closed, texte bloqué par précaution",
                        e.getMessage());
                return false;
            }
        }
    }
}
