package ru.it_spectrum.ai.loki.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;
import ru.it_spectrum.ai.loki.mcp.service.ConnectionsService;

@Component
public class ConnectionTools {
    private final ConnectionsService service;

    public ConnectionTools(ConnectionsService service) {
        this.service = service;
    }

    @McpTool(name = "listConnections",
            description = "List configured Loki stands with each name, description, operator hint and effective tool limits. "
                    + "Call this first, use the shown windows and page limit, and pass the chosen name as 'connection' "
                    + "to every other tool.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public String listConnections() {
        return service.list();
    }
}
