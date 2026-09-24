package ru.it_spectrum.ai.loki.mcp.service;

import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.connection.LineLayout;

@Service
public class ConnectionsService {
    private final ConnectionRegistry registry;

    public ConnectionsService(ConnectionRegistry registry) {
        this.registry = registry;
    }

    /**
     * One line per connection: name, description, the operator's hint and what service/level/text queries start from, and the
     * layouts exportLogs can write.
     * Never URLs or credentials.
     */
    public String list() {
        var text = new StringBuilder();
        for (var connection : registry.list()) {
            text.append(connection.name());
            if (connection.description() != null) text.append(" — ").append(connection.description());
            if (connection.hint() != null) text.append(". ").append(connection.hint());
            if (text.charAt(text.length() - 1) != '.') text.append('.');
            text.append(" Without LogQL: service, level (").append(String.join(", ", connection.allLevels().keySet())).append("), text")
                    .append(connection.scope() == null ? "; service is required." : ", searched in " + connection.scope() + ".");
            if (!connection.layouts().isEmpty())
                text.append(" exportLogs formats: raw, ").append(String.join(", ", connection.layouts().stream().map(LineLayout::id).toList())).append('.');
            text.append('\n');
        }
        return text.toString().stripTrailing();
    }
}
