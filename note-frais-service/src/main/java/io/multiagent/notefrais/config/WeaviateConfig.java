package io.multiagent.notefrais.config;

import io.weaviate.client.Config;
import io.weaviate.client.WeaviateClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;

@Configuration
@Slf4j
@EnableScheduling
public class WeaviateConfig {

    @Value("${weaviate.schema-init.max-attempts:6}")
    private int schemaInitMaxAttempts;

    @Value("${weaviate.schema-init.backoff:10s}")
    private Duration schemaInitBackoff;

    @Bean
    public WeaviateClient weaviateClient(
            @Value("${weaviate.scheme:http}") String scheme,
            @Value("${weaviate.host:localhost:8080}") String host) {
        return new WeaviateClient(new Config(scheme, sanitizeHost(host)));
    }

    private String sanitizeHost(String rawHost) {
        if (rawHost == null) {
            return null;
        }
        String sanitized = rawHost.trim();
        if (sanitized.isEmpty()) {
            return sanitized;
        }
        if (sanitized.startsWith("http://")) {
            sanitized = sanitized.substring("http://".length());
        } else if (sanitized.startsWith("https://")) {
            sanitized = sanitized.substring("https://".length());
        }
        if (sanitized.endsWith("/")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        return sanitized;
    }
}
