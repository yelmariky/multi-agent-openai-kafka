package io.multiagent.core.mcp;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Corrèle une session MCP (SSE) à son tenant, le temps d'une requête.
 *
 * Nécessaire car le transport SSE de spring-ai-starter-mcp-server-webmvc exécute le corps des
 * outils {@code @Tool} sur un thread Reactor ({@code boundedElastic}) distinct de celui qui a
 * authentifié la requête HTTP {@code POST /mcp/message} — {@link io.multiagent.core.infrastructure
 * .tenant.TenantContext} (ThreadLocal) ne survit donc pas jusqu'à l'exécution de l'outil.
 * {@link McpApiKeyFilter} alimente ce registre (clé = paramètre {@code sessionId} de la requête,
 * déjà présent sur chaque appel) ; {@link ExpenseInvoiceMcpTools} le consulte en secours quand le
 * ThreadLocal est vide.
 */
@Component
public class McpSessionTenantRegistry {

    private final Cache<String, UUID> cache = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();

    public void put(String sessionId, UUID tenantId) {
        if (sessionId != null && tenantId != null) {
            cache.put(sessionId, tenantId);
        }
    }

    public UUID get(String sessionId) {
        return sessionId != null ? cache.getIfPresent(sessionId) : null;
    }
}
