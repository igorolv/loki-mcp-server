package ru.it_spectrum.ai.loki.mcp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionsLoader;
import ru.it_spectrum.ai.loki.mcp.service.Errors;
import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
public class ConnectionsConfig {
    @Bean
    ConnectionRegistry connectionRegistry(@Value("${loki-mcp.connections-file}") String file) {
        try {
            return new ConnectionRegistry(ConnectionsLoader.load(Path.of(file), System::getenv));
        } catch (Exception ignored) {
            throw Errors.configuration();
        }
    }
}
