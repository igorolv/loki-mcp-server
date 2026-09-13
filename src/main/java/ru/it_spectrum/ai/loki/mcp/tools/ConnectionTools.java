package ru.it_spectrum.ai.loki.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import ru.it_spectrum.ai.loki.mcp.model.ListConnectionsResult;
import ru.it_spectrum.ai.loki.mcp.service.ConnectionsService;

public class ConnectionTools {
    private final ConnectionsService service;
    public ConnectionTools(ConnectionsService service) { this.service = service; }

    @McpTool(name = "listConnections", generateOutputSchema = true,
            description = "Discover configured connection names and descriptions before reading Loki. "
                    + "Pass a name explicitly to data tools. This returns local configuration only, "
                    + "without URLs or credentials; it does not contact Loki or verify availability.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public ListConnectionsResult listConnections() { return service.list(); }
}
