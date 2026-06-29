package io.multiagent.activity.config;

import io.multiagent.activity.infrastructure.tenant.TenantFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Multi-realm JWT validation via JwtIssuerAuthenticationManagerResolver.
 * Each Keycloak realm is an accepted issuer — the resolver validates dynamically.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String ROLE_ADMIN   = "admin";
    private static final String ROLE_MANAGER = "manager";

    private final TenantFilter tenantFilter;
    private final String keycloakInternalUrl;

    public SecurityConfig(TenantFilter tenantFilter,
                          @Value("${keycloak.internal-url:${keycloak.base-url:http://localhost:8090}}") String keycloakInternalUrl) {
        this.tenantFilter = tenantFilter;
        this.keycloakInternalUrl = keycloakInternalUrl;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())   // API REST stateless JWT — CSRF non applicable (RFC 6750)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .cors(cors -> cors.configure(http))
            // ── OWASP Security Headers ──────────────────────────────────────────────
            .headers(h -> h
                .frameOptions(f -> f.deny())                           // X-Frame-Options: DENY
                .contentTypeOptions(c -> {})                           // X-Content-Type-Options: nosniff
                .referrerPolicy(r ->
                    r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                        .ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .addHeaderWriter((req, res) ->
                    res.setHeader("Content-Security-Policy",
                        "default-src 'none'; frame-ancestors 'none'"))
                .addHeaderWriter((req, res) ->
                    res.setHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=()"))
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                .requestMatchers("/admin/notifications/stream", "/consultant/notifications/stream").permitAll()
                .requestMatchers("/admin/notifications/**").hasAnyRole(ROLE_ADMIN, ROLE_MANAGER)
                .requestMatchers(HttpMethod.POST, "/expenses/approve", "/expenses/refuse").hasAnyRole(ROLE_ADMIN, ROLE_MANAGER)
                .requestMatchers(HttpMethod.POST, "/cra/validate", "/cra/refuse", "/cra/reopen").hasAnyRole(ROLE_ADMIN, ROLE_MANAGER)
                .requestMatchers(HttpMethod.POST, "/invoices/delete").hasRole(ROLE_ADMIN)
                .requestMatchers(HttpMethod.POST, "/consultants/invite").hasRole(ROLE_ADMIN)
                .requestMatchers(HttpMethod.DELETE, "/consultants/profiles").hasRole(ROLE_ADMIN)
                .requestMatchers(HttpMethod.GET, "/consultants/keycloak-users").hasAnyRole(ROLE_ADMIN, ROLE_MANAGER)
                .requestMatchers("/settings/seller-profile", "/settings/seller", "/settings/reindex-chunks").hasRole(ROLE_ADMIN)
                // Dashboard manager
                .requestMatchers("/dashboard/**").hasAnyRole(ROLE_ADMIN, ROLE_MANAGER)
                // Congés — consultant peut soumettre et consulter, admin peut approuver/refuser/gérer les soldes
                .requestMatchers(HttpMethod.POST, "/leaves/request").authenticated()
                .requestMatchers(HttpMethod.GET,  "/leaves/mine", "/leaves/balance").authenticated()
                .requestMatchers(HttpMethod.PUT,  "/leaves/balance").hasAnyRole(ROLE_ADMIN, ROLE_MANAGER)
                .requestMatchers(HttpMethod.PUT,  "/leaves/*/approve", "/leaves/*/refuse").hasAnyRole(ROLE_ADMIN, ROLE_MANAGER)
                .requestMatchers(HttpMethod.GET,  "/leaves").hasAnyRole(ROLE_ADMIN, ROLE_MANAGER)
                // Platform admin endpoints
                .requestMatchers("/platform/**").hasRole("platform_admin")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .authenticationManagerResolver(multiRealmAuthManagerResolver())
            )
            // TenantFilter must run AFTER BearerTokenAuthenticationFilter so SecurityContext is populated
            .addFilterAfter(tenantFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** Prevent Spring Boot from auto-registering TenantFilter as a servlet filter (outside Security chain). */
    @Bean
    FilterRegistrationBean<TenantFilter> disableTenantFilterAutoRegistration(TenantFilter filter) {
        FilterRegistrationBean<TenantFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    JwtIssuerAuthenticationManagerResolver multiRealmAuthManagerResolver() {
        Map<String, AuthenticationManager> cache = new ConcurrentHashMap<>();

        return new JwtIssuerAuthenticationManagerResolver(issuer ->
            cache.computeIfAbsent(issuer, iss -> {
                // Extract realm from issuer (e.g. "http://localhost:8090/realms/ia-insight" -> "ia-insight")
                String realm = iss.substring(iss.lastIndexOf("/realms/") + "/realms/".length());
                // Fetch JWKS from K8s-internal Keycloak URL (reachable from the pod)
                String jwksUri = keycloakInternalUrl + "/realms/" + realm + "/protocol/openid-connect/certs";

                NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
                // Validate timestamps + accept the original issuer from the token
                decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                        new JwtTimestampValidator(),
                        new JwtIssuerValidator(iss)
                ));

                JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
                provider.setJwtAuthenticationConverter(jwtAuthenticationConverter());
                return provider::authenticate;
            })
        );
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var realmAccess = jwt.getClaimAsMap("realm_access");
            if (realmAccess == null) return List.of();
            @SuppressWarnings("unchecked")
            var roles = (List<String>) realmAccess.get("roles");
            if (roles == null) return List.of();
            return roles.stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .collect(Collectors.toList());
        });
        return converter;
    }
}
