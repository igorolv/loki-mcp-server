package ru.it_spectrum.ai.loki.mcp.config;

import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class McpServerConfig {

    @Bean
    McpSyncServerCustomizer immediateStdioExecution() {
        // Serialize synchronous handling on the stdio path, as in the donor servers.
        return builder -> builder.immediateExecution(true);
    }
}
