package ru.it_spectrum.ai.loki.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import ru.it_spectrum.ai.loki.mcp.service.QueryService;

@Component
public class QueryTools {
    static final String QUERY = "LogQL log query, e.g. {app=\"backend\"} |= \"ERROR\". Take label names and values from discoverLogs.";
    static final String START = "Window start, default now-1h. Examples: now-15m, now-2d, 2026-09-13T10:00:00+03:00.";
    static final String END = "Window end, default now. Use the end in a queryLogs footer to read older lines.";
    private final QueryService service;

    public QueryTools(QueryService service) {
        this.service = service;
    }

    @McpTool(name = "queryLogs",
            description = "Read a bounded page of log lines matching a LogQL query in a window. The response is chronological, "
                    + "with a compact level, service and message view. Example: {app=\"backend\"} |= \"ERROR\". "
                    + "Use raw=true to preview the line returned by Loki with its stream labels; long lines are shortened. "
                    + "Use order=\"oldest\" to read forward, repeat with the start or end value in the footer, and use exportLogs for complete files.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public String queryLogs(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = QUERY) String query,
            @McpToolParam(description = START, required = false) String start,
            @McpToolParam(description = END, required = false) String end,
            @McpToolParam(description = "Maximum lines to show, default 50", required = false) Integer limit,
            @McpToolParam(description = "Preview the line returned by Loki with stream labels, default false", required = false) Boolean raw,
            @McpToolParam(description = "newest (default) or oldest; oldest reads forward", required = false) String order) {
        return service.logs(connection, query, start, end, limit, raw, order);
    }

    @McpTool(name = "countLogs",
            description = "Count log lines matching a LogQL log query without returning those lines. "
                    + "Example: query={app=\"backend\"} |= \"ERROR\", groupBy=\"time\". "
                    + "Omit groupBy for one total, use a label for values, time for buckets, or app,time for both; step=\"1d\" sets the bucket width. "
                    + "For a multi-day label count, query one-day windows separately; only time-only buckets allow seven days by default. "
                    + "A named LogQL regexp capture can supply a groupBy label; inspect matching lines with queryLogs.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public String countLogs(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = QUERY) String query,
            @McpToolParam(description = START, required = false) String start,
            @McpToolParam(description = END, required = false) String end,
            @McpToolParam(description = "Label name, time, or <label>,time", required = false) String groupBy,
            @McpToolParam(description = "Bucket width for time grouping, 1s through 1d; default automatic", required = false) String step) {
        return service.count(connection, query, start, end, groupBy, step);
    }
}
