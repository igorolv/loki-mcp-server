package ru.it_spectrum.ai.loki.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import ru.it_spectrum.ai.loki.mcp.service.DiscoveryService;

/**
 * Registered only through the safe QueryToolsConfig wrapper.
 */
public class DiscoveryTools {
    private final DiscoveryService service;

    public DiscoveryTools(DiscoveryService service) {
        this.service = service;
    }

    @McpTool(name = "discoverLogs",
            description = "See what logs exist before writing a query: label names and their values, whether lines are JSON or plain text, "
                    + "which JSON fields and levels appear, one example line, and a suggested selector for the next call. "
                    + "Call it without a selector first to list all labels of the stand, then with a selector like {app=\"backend\"} "
                    + "to look inside one service. The overview shows at most 10 values per label; to get every value of one label "
                    + "(e.g. all services) pass label=\"applicationName\" and nothing but the values is returned. "
                    + "A label missing here may still exist in another time window.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public String discoverLogs(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = "Optional stream selector to narrow the scope, e.g. {namespace=\"dev\"}. Selector only, no filters. Omit to list all labels", required = false) String selector,
            @McpToolParam(description = QueryTools.START, required = false) String start,
            @McpToolParam(description = QueryTools.END, required = false) String end,
            @McpToolParam(description = "Optional label name, e.g. \"applicationName\": list all its values (up to 200) instead of the overview", required = false) String label) {
        return service.discover(connection, selector, start, end, label);
    }
}
