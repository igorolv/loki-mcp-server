package ru.it_spectrum.ai.loki.mcp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionsLoader;
import ru.it_spectrum.ai.loki.mcp.connection.ExportRoots;
import ru.it_spectrum.ai.loki.mcp.service.Errors;

import java.nio.file.Path;
import java.util.stream.Collectors;

@Configuration(proxyBeanMethods = false)
public class ConnectionsConfig {
    private static final Logger log = LoggerFactory.getLogger(ConnectionsConfig.class);

    @Bean
    ConnectionsLoader.Config connectionsFile(@Value("${loki-mcp.connections-file}") String file) {
        try {
            return ConnectionsLoader.loadConfig(Path.of(file), System::getenv);
        } catch (Exception ignored) {
            log.error("Cannot load connections: check the connections file (LOKI_MCP_CONNECTIONS_FILE) against examples/connections.json");
            throw Errors.configuration();
        }
    }

    @Bean
    ConnectionRegistry connectionRegistry(ConnectionsLoader.Config config) {
        var registry = new ConnectionRegistry(config.connections());
        // Names, timezone and auth type only: URLs, credentials and tenants stay out of the log.
        log.info("Configured connections: {}", registry.list().stream()
                .map(c -> c.name() + " (" + c.timezone() + ", auth " + c.auth().type() + ")").collect(Collectors.joining(", ")));
        return registry;
    }

    /**
     * The configured export roots, or unrestricted destinations with a default under the data directory.
     */
    @Bean
    ExportRoots exportRoots(ConnectionsLoader.Config config, @Value("${loki-mcp.data-dir}") String dataDir) {
        Path fallback = Path.of(dataDir).toAbsolutePath().resolve("exports");
        Path defaultDirectory = config.exportRoots().isEmpty() ? fallback : config.exportRoots().getFirst();
        return new ExportRoots(defaultDirectory, config.exportRoots());
    }
}
