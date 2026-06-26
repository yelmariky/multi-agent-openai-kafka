package io.multiagent.invoice.infrastructure.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Slf4j
@Component
public class TenantFilter extends OncePerRequestFilter {

    private static final Set<String> PUBLIC_PATHS = Set.of("/actuator");

    private final OrganizationRepository organizationRepository;

    public TenantFilter(OrganizationRepository organizationRepository) {
        this.organizationRepository = organizationRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwtAuth) {
                Jwt jwt = jwtAuth.getToken();
                String issuer = jwt.getIssuer() != null ? jwt.getIssuer().toString() : null;
                String realm = extractRealmFromIssuer(issuer);
                if (realm != null) {
                    organizationRepository.findByKeycloakRealm(realm).ifPresent(org ->
                            TenantContext.set(org.getId(), realm)
                    );
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    static String extractRealmFromIssuer(String issuer) {
        if (issuer == null || !issuer.contains("/realms/")) {
            return null;
        }
        String[] parts = issuer.split("/realms/");
        if (parts.length < 2 || parts[1].isBlank()) {
            return null;
        }
        String realm = parts[1].split("/")[0];
        return realm.isBlank() ? null : realm;
    }
}
