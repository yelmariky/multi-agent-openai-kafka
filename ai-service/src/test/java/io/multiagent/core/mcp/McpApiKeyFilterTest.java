package io.multiagent.core.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpApiKeyFilterTest {

    @Test
    void parsesSingleKeyPair() {
        Map<String, String> map = McpApiKeyFilter.parseApiKeys("ia-insight:sk-abc123");
        assertThat(map).containsExactly(Map.entry("sk-abc123", "ia-insight"));
    }

    @Test
    void parsesMultipleKeyPairs() {
        Map<String, String> map = McpApiKeyFilter.parseApiKeys("tenant-a:key-a,tenant-b:key-b");
        assertThat(map).containsEntry("key-a", "tenant-a").containsEntry("key-b", "tenant-b");
    }

    @Test
    void ignoresBlankConfig() {
        assertThat(McpApiKeyFilter.parseApiKeys("")).isEmpty();
        assertThat(McpApiKeyFilter.parseApiKeys(null)).isEmpty();
    }

    @Test
    void ignoresMalformedPairs() {
        Map<String, String> map = McpApiKeyFilter.parseApiKeys("no-colon-here,tenant-a:key-a,:missing-slug,tenant-c:");
        assertThat(map).containsExactly(Map.entry("key-a", "tenant-a"));
    }

    @Test
    void masksKeyKeepingOnlyEdges() {
        assertThat(McpApiKeyFilter.maskKey("sk-1234567890")).isEqualTo("sk-***890");
    }

    @Test
    void masksShortOrNullKeyAsStars() {
        assertThat(McpApiKeyFilter.maskKey("abc")).isEqualTo("***");
        assertThat(McpApiKeyFilter.maskKey(null)).isEqualTo("***");
    }
}
