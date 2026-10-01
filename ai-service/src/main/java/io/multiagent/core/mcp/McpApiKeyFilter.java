package io.multiagent.core.mcp;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerSession;
import io.multiagent.core.infrastructure.tenant.TenantContext;
import io.multiagent.core.organization.entity.Organization;
import io.multiagent.core.organization.repository.OrganizationRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Authentifie les requêtes du serveur MCP (systèmes externes) via une clé API dédiée par tenant —
 * pas de JWT ici, ces appelants ne passent pas par un login Keycloak. Résout le tenant depuis la
 * clé et pose {@link TenantContext} exactement comme {@code TenantFilter} le fait depuis un JWT,
 * pour que les repositories JPA restent tenant-scopés de façon identique dans tout le code.
 */
@Slf4j
@Component
@Order(2)
public class McpApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-MCP-Api-Key";
    // Transport SSE (spring-ai-starter-mcp-server-webmvc) : GET /sse établit le flux,
    // POST /mcp/message envoie les messages JSON-RPC — deux chemins distincts à couvrir.
    private static final List<String> PATH_PREFIXES = List.of("/mcp", "/sse");

    private final OrganizationRepository organizationRepository;
    private final McpSessionTenantRegistry sessionTenantRegistry;
    private final WebMvcSseServerTransportProvider sseTransportProvider;
    private final Map<String, String> apiKeyToRealm;
    private final int maxRequestsPerMinute;

    // Résolu une seule fois (réflexion coûteuse) : accès au champ privé "sessions" du transport —
    // seul moyen d'obtenir le McpServerSession interne (et son id, DIFFÉRENT du sessionId HTTP de
    // l'URL) à partir du sessionId HTTP, voir resolveInternalSessionId().
    private final Field sseSessionsField;

    private final Cache<String, AtomicInteger> rateLimitCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();

    public McpApiKeyFilter(
            OrganizationRepository organizationRepository,
            McpSessionTenantRegistry sessionTenantRegistry,
            WebMvcSseServerTransportProvider sseTransportProvider,
            @Value("${ai-core.mcp.api-keys:}") String apiKeysConfig,
            @Value("${ai-core.mcp.max-requests-per-minute:30}") int maxRequestsPerMinute) {
        this.organizationRepository = organizationRepository;
        this.sessionTenantRegistry = sessionTenantRegistry;
        this.sseTransportProvider = sseTransportProvider;
        this.apiKeyToRealm = parseApiKeys(apiKeysConfig);
        this.maxRequestsPerMinute = maxRequestsPerMinute;
        this.sseSessionsField = resolveSessionsField(WebMvcSseServerTransportProvider.class);
    }

    private static Field resolveSessionsField(Class<?> transportClass) {
        try {
            Field f = transportClass.getDeclaredField("sessions");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException e) {
            log.warn("🔌 [MCP] Impossible d'accéder au champ interne 'sessions' de {} (SDK MCP a peut-être changé) : {}",
                    transportClass.getSimpleName(), e.getMessage());
            return null;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String apiKey = request.getHeader(HEADER);
            if (isBlank(apiKey)) {
                reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Clé API manquante (en-tête " + HEADER + ")");
                return;
            }
            String realm = apiKeyToRealm.get(apiKey);
            if (realm == null) {
                log.warn("🔌 [MCP] Clé API invalide : {}", maskKey(apiKey));
                reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Clé API invalide");
                return;
            }
            if (!withinRateLimit(apiKey)) {
                log.warn("🔌 [MCP] Rate limit dépassé pour {}", maskKey(apiKey));
                reject(response, 429, "Trop de requêtes — réessayez plus tard");
                return;
            }
            Optional<Organization> orgOpt = organizationRepository.findByKeycloakRealm(realm);
            if (orgOpt.isEmpty() || Boolean.FALSE.equals(orgOpt.get().getActive())) {
                reject(response, HttpServletResponse.SC_FORBIDDEN, "Organisation introuvable ou désactivée");
                return;
            }
            Organization org = orgOpt.get();
            TenantContext.set(org.getId(), realm);
            // Filet de sécurité pour l'exécution des outils MCP sur un thread Reactor distinct —
            // voir McpSessionTenantRegistry. Le sessionId HTTP (paramètre de /mcp/message) et l'id
            // interne de McpServerSession sont deux identifiants DIFFÉRENTS dans ce SDK : on résout
            // le second via le registre interne du transport avant de peupler le registre, pour que
            // ExpenseInvoiceMcpTools (qui relit l'id via McpSyncServerExchange.sessionId()) le retrouve.
            String httpSessionId = request.getParameter("sessionId");
            String internalSessionId = resolveInternalSessionId(httpSessionId);
            sessionTenantRegistry.put(internalSessionId, org.getId());
            log.info("🔌 [MCP] requête authentifiée — tenant={} apiKey={}", realm, maskKey(apiKey));
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Traduit le sessionId HTTP (paramètre {@code ?sessionId=} de /mcp/message) en id interne
     * {@link McpServerSession#getId()} via le registre privé du transport — {@code null} si la
     * session n'existe pas encore (ex: tout premier message GET /sse) ou si la réflexion a échoué
     * à l'initialisation (SDK incompatible).
     */
    private String resolveInternalSessionId(String httpSessionId) {
        if (httpSessionId == null || sseSessionsField == null) return null;
        try {
            @SuppressWarnings("unchecked")
            ConcurrentHashMap<String, McpServerSession> sessions =
                    (ConcurrentHashMap<String, McpServerSession>) sseSessionsField.get(sseTransportProvider);
            McpServerSession session = sessions.get(httpSessionId);
            return session != null ? session.getId() : null;
        } catch (ReflectiveOperationException | ClassCastException e) {
            log.warn("🔌 [MCP] Résolution de session SSE interne échouée : {}", e.getMessage());
            return null;
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return PATH_PREFIXES.stream().noneMatch(uri::startsWith);
    }

    private boolean withinRateLimit(String apiKey) {
        AtomicInteger counter = rateLimitCache.get(apiKey, k -> new AtomicInteger(0));
        return counter.incrementAndGet() <= maxRequestsPerMinute;
    }

    static Map<String, String> parseApiKeys(String config) {
        Map<String, String> map = new HashMap<>();
        if (config == null || config.isBlank()) return map;
        for (String pair : config.split(",")) {
            String[] parts = pair.trim().split(":", 2);
            if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                map.put(parts[1].trim(), parts[0].trim()); // clé API -> realm
            }
        }
        return map;
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }

    static String maskKey(String key) {
        if (key == null || key.length() < 6) return "***";
        return key.substring(0, 3) + "***" + key.substring(key.length() - 3);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
