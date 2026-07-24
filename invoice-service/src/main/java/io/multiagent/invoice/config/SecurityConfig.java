package io.multiagent.invoice.config;

import io.multiagent.invoice.infrastructure.tenant.TenantFilter;
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

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String ROLE_ADMIN = "admin";
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
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .cors(cors -> cors.configure(http))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                .requestMatchers(HttpMethod.POST, "/invoices/delete", "/invoices/delete-by-text").hasRole(ROLE_ADMIN)
                // Facturation des abonnements SaaS — console plateforme ou admin IA-INSIGHT
                .requestMatchers(HttpMethod.POST, "/invoices/subscription/run-billing").hasAnyRole(ROLE_ADMIN, "platform_admin")
                // Relances de factures impayées — admin/manager (relance manuelle et batch)
                .requestMatchers(HttpMethod.POST, "/invoices/run-dunning").hasAnyRole(ROLE_ADMIN, ROLE_MANAGER, "platform_admin")
                .requestMatchers(HttpMethod.POST, "/invoices/*/dunning").hasAnyRole(ROLE_ADMIN, ROLE_MANAGER)
                // Console plateforme — suivi des factures d'abonnement du tenant vendeur
                .requestMatchers("/invoices/platform/**").hasRole("platform_admin")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .authenticationManagerResolver(multiRealmAuthManagerResolver())
            )
            .addFilterAfter(tenantFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

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
                String realm = iss.substring(iss.lastIndexOf("/realms/") + "/realms/".length());
                String jwksUri = keycloakInternalUrl + "/realms/" + realm + "/protocol/openid-connect/certs";

                NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
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
