package ru.it_spectrum.ai.loki.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import ru.it_spectrum.ai.loki.mcp.service.ConnectionsService;

public class ConnectionTools {
    private final ConnectionsService service;

    public ConnectionTools(ConnectionsService service) {
        this.service = service;
    }

    @McpTool(name = "listConnections",
            description = "List the configured Loki stands: one line per connection with its name, description and hints about "
                    + "its labels. Call this first and pass the chosen name as 'connection' to every other tool.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public String listConnections() {
        return service.list();
    }
}
