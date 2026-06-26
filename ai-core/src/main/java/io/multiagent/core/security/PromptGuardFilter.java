package io.multiagent.core.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Filtre de sécurité global appliqué à toutes les requêtes POST/PUT
 * AVANT qu'elles atteignent un controller.
 *
 * Protège contre : prompt injection, token flooding, rate limiting.
 * Fonctionne sur tous les endpoints — aucune configuration par controller requise.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class PromptGuardFilter extends OncePerRequestFilter {

    // Endpoints exclus du guard (fichiers binaires, multipart, auth)
    private static final Set<String> EXCLUDED_PREFIXES = Set.of(
            "/actuator", "/error", "/receipts/upload"
    );

    // Champs JSON contenant du texte libre à inspecter
    private static final Set<String> TEXT_FIELDS = Set.of("text", "message", "query", "content");

    private final PromptGuard promptGuard;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws IOException, jakarta.servlet.ServletException {

        String method = request.getMethod();
        String path   = request.getRequestURI();

        // Passe directement pour GET, DELETE, OPTIONS et les paths exclus
        if (!"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method)) {
            chain.doFilter(request, response);
            return;
        }
        if (isExcluded(path)) {
            chain.doFilter(request, response);
            return;
        }

        // ── Lecture du corps (une seule fois) ────────────────────────────────
        byte[] rawBytes = request.getInputStream().readAllBytes();
        String contentType = request.getContentType();

        // ── Extraction du texte à vérifier ───────────────────────────────────
        String textToCheck  = extractText(rawBytes, contentType);
        String consultantEmail = extractField(rawBytes, "consultantEmail");

        if (textToCheck != null && !textToCheck.isBlank()) {
            PromptGuard.GuardResult guard = promptGuard.check(textToCheck, consultantEmail);
            if (!guard.allowed()) {
                sendRejection(response, guard.rejectionReason(), path);
                return;
            }
            // Remplacer le texte par la version assainie dans le corps
            rawBytes = replaceSanitized(rawBytes, contentType, textToCheck, guard.sanitizedText());
        }

        // ── Passer la requête avec le corps mis en cache ──────────────────────
        chain.doFilter(new CachedBodyHttpServletRequest(request, rawBytes), response);
    }

    // ── Helpers privés ────────────────────────────────────────────────────────

    private boolean isExcluded(String path) {
        return EXCLUDED_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private String extractText(byte[] body, String contentType) {
        if (body == null || body.length == 0) return null;
        try {
            if (contentType != null && contentType.contains(MediaType.APPLICATION_JSON_VALUE)) {
                JsonNode node = objectMapper.readTree(body);
                // Cherche le premier champ texte connu
                for (String field : TEXT_FIELDS) {
                    if (node.hasNonNull(field)) {
                        return node.get(field).asText(null);
                    }
                }
            }
            // Texte brut
            String raw = new String(body, StandardCharsets.UTF_8);
            if (!raw.trim().startsWith("{") && !raw.trim().startsWith("[")) {
                return raw;
            }
        } catch (Exception ignored) {
            // Corps non parsable → pas d'inspection
        }
        return null;
    }

    private String extractField(byte[] body, String field) {
        if (body == null || body.length == 0) return null;
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node.hasNonNull(field)) return node.get(field).asText(null);
        } catch (Exception ignored) { /* not JSON */ }
        return null;
    }

    /**
     * Remplace dans le corps JSON le texte original par sa version assainie.
     * Retourne le corps inchangé si le remplacement échoue.
     */
    private byte[] replaceSanitized(byte[] body, String contentType, String original, String sanitized) {
        if (original.equals(sanitized)) return body;
        try {
            if (contentType != null && contentType.contains(MediaType.APPLICATION_JSON_VALUE)) {
                JsonNode node = objectMapper.readTree(body);
                if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode obj) {
                    for (String field : TEXT_FIELDS) {
                        if (obj.hasNonNull(field)) {
                            obj.put(field, sanitized);
                            return objectMapper.writeValueAsBytes(obj);
                        }
                    }
                }
            }
        } catch (Exception ignored) { /* keep original */ }
        return sanitized.getBytes(StandardCharsets.UTF_8);
    }

    private void sendRejection(HttpServletResponse response, String reason, String path) throws IOException {
        log.warn("🛡️ [GUARD FILTER] Requête rejetée sur {} — {}", path, reason);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + escapeJson(reason) + "\"}");
    }

    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
