package io.multiagent.core.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Protège les endpoints admin sensibles avec un header statique X-Admin-Key.
 * Si AI_CORE_ADMIN_KEY n'est pas configuré, l'intercepteur est désactivé
 * (compatibilité phase 1). Phase 2 : remplacer par validation JWT Keycloak.
 */
@Slf4j
public class AdminKeyInterceptor implements HandlerInterceptor {

    static final String HEADER = "X-Admin-Key";

    private final String adminKey;

    public AdminKeyInterceptor(String adminKey) {
        this.adminKey = adminKey;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (adminKey == null || adminKey.isBlank()) {
            log.warn("⚠️ AI_CORE_ADMIN_KEY non configuré — endpoint admin non protégé : {}", request.getRequestURI());
            return true;
        }
        String provided = request.getHeader(HEADER);
        if (!adminKey.equals(provided)) {
            log.warn("🚫 Accès refusé à {} — header {} manquant ou invalide", request.getRequestURI(), HEADER);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Unauthorized\"}");
            return false;
        }
        return true;
    }
}
