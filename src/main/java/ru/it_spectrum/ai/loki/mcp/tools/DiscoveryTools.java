package ru.it_spectrum.ai.loki.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import ru.it_spectrum.ai.loki.mcp.service.DiscoveryService;

@Component
public class DiscoveryTools {
    private final DiscoveryService service;

    public DiscoveryTools(DiscoveryService service) {
        this.service = service;
    }

    @McpTool(name = "discoverLogs",
            description = "Discover label names and values before writing LogQL. Call without label to list label names, "
                    + "then pass label=\"app\" to list its values. Use the names and values to write a stream selector "
                    + "for queryLogs or countLogs. Widen the window if a quiet stand has no labels in the last hour.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public String discoverLogs(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = QueryTools.START, required = false) String start,
            @McpToolParam(description = QueryTools.END, required = false) String end,
            @McpToolParam(description = "Optional label name, e.g. \"app\": list its values", required = false) String label) {
        return service.discover(connection, start, end, label);
    }
}
