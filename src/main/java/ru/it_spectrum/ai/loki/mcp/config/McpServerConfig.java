package ru.it_spectrum.ai.loki.mcp.config;

import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import tools.jackson.databind.json.JsonMapper;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.service.ResponseBudget;

@Configuration(proxyBeanMethods = false)
public class McpServerConfig {

    @Bean
    McpServerTransportProvider budgetedStdioTransport(
            @Qualifier("mcpServerJsonMapper") JsonMapper mapper, ConnectionRegistry registry) {
        return new BudgetedStdioTransport(new JacksonMcpJsonMapper(mapper), new ResponseBudget(mapper, registry));
    }

    @Bean
    McpSyncServerCustomizer immediateStdioExecution() {
        // Serialize synchronous handling on the stdio path, as in the donor servers.
        // Input validation runs inside our safe tool boundary; SDK validation logs raw diagnostics.
        return builder -> builder.immediateExecution(true).validateToolInputs(false);
    }
}
