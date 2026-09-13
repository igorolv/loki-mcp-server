package ru.it_spectrum.ai.loki.mcp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;

@Configuration(proxyBeanMethods = false)
public class LokiClientConfig {
    @Bean(destroyMethod = "close")
    LokiHttpClient lokiHttpClient(ConnectionRegistry registry) {
        return new LokiHttpClient(registry);
    }
}
