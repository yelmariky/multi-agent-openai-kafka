package io.multiagent.core.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Enregistre {@link ExpenseInvoiceMcpTools} auprès du serveur MCP auto-configuré (spring-ai-starter-mcp-server-webmvc). */
@Configuration
public class McpConfig {

    @Bean
    public ToolCallbackProvider expenseInvoiceMcpToolCallbacks(ExpenseInvoiceMcpTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
