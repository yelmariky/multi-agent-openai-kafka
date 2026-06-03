package io.multiagent.core.config;

import io.weaviate.client.Config;
import io.weaviate.client.WeaviateClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WeaviateConfig {

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
