package ru.it_spectrum.ai.loki.mcp.config;

import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class McpServerConfig {

    /**
     * Runs tool calls one at a time on the stdio reader thread. MCP SDK 2.0.x drops responses of concurrent tool calls
     * on stdio (java-sdk #686, fixed upstream in 2bb1481, not released in 2.0.1); remove once the SDK that Spring AI
     * brings contains the fix.
     */
    @Bean
    McpSyncServerCustomizer immediateStdioExecution() {
        return builder -> builder.immediateExecution(true);
    }
}
