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
            description = "Discover labels before writing LogQL. Without label or match, list label names; "
                    + "label=\"app\" lists its values. match=\"{app=\\\"api\\\"}\" lists complete stream label sets; "
                    + "add label=\"namespace\" to list namespace values among those sets. "
                    + "Use a narrow match and time window, then countLogs or queryLogs to check log lines.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public String discoverLogs(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = QueryTools.START, required = false) String start,
            @McpToolParam(description = QueryTools.END, required = false) String end,
            @McpToolParam(description = "Optional label name, e.g. \"app\": list its values", required = false) String label,
            @McpToolParam(description = "Optional LogQL stream selector, e.g. {app=\"api\"}; no line filters", required = false) String match) {
        return service.discover(connection, start, end, label, match);
    }
}
