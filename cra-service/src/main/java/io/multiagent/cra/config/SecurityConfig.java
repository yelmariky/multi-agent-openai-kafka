package io.multiagent.cra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;
import java.util.stream.Collectors;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String ROLE_ADMIN   = "admin";
    private static final String ROLE_MANAGER = "manager";

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // CORS géré par WebCorsConfig (WebMvcConfigurer) — Spring Security doit le laisser passer
            .cors(cors -> cors.configure(http))
            .authorizeHttpRequests(auth -> auth
                // Kubernetes liveness/readiness probes et Prometheus — publics
                .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                // Validation / refus / réouverture CRA = admin ou manager
                .requestMatchers(HttpMethod.POST, "/cra/validate", "/cra/refuse", "/cra/reopen").hasAnyRole(ROLE_ADMIN, ROLE_MANAGER)
                // Tout le reste = authentifié (consultant, manager ou admin)
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            );
        return http.build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        // Keycloak expose les rôles dans realm_access.roles (pas dans le claim "roles" standard)
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
