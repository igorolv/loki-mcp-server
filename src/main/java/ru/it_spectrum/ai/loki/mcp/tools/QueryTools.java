package ru.it_spectrum.ai.loki.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import ru.it_spectrum.ai.loki.mcp.service.QueryService;

/** Registered through QueryToolsConfig, which turns every failure into a short safe text error. */
public class QueryTools {
    static final String START = "Window start. Default \"now-1h\". Examples: \"now-15m\", \"now-2d\", \"2026-09-13T10:00:00+03:00\".";
    static final String END = "Window end. Default \"now\". Same formats as start; use the value from a previous footer to read older lines.";
    private final QueryService service;
    public QueryTools(QueryService service) { this.service = service; }

    @McpTool(name = "queryLogs",
            description = "Read log lines matching a LogQL log query, newest first within the window. Each line is printed as "
                    + "'time LEVEL service message' with stack traces shortened. Example query: {app=\"backend\"} |= \"ERROR\"; "
                    + "with JSON logs add | json | log_level=~\"(?i)error\". To find lines of one request use |= \"<traceId>\". "
                    + "If the footer says there are more lines, either narrow the query or repeat with the end value it gives. "
                    + "Use raw=true with a narrow query to see the complete original line.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public String queryLogs(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = "LogQL log query starting with a stream selector, e.g. {app=\"backend\"} |= \"ERROR\"") String query,
            @McpToolParam(description = START, required = false) String start,
            @McpToolParam(description = END, required = false) String end,
            @McpToolParam(description = "Maximum lines to return, default 50", required = false) Integer limit,
            @McpToolParam(description = "true prints original lines unchanged (full JSON, full stack trace). Default false", required = false) Boolean raw) {
        return service.logs(connection, query, start, end, limit, raw);
    }

    @McpTool(name = "countLogs",
            description = "Count log lines matching a LogQL log query, to check whether a problem exists, how big it is and when it started. "
                    + "Without groupBy returns one number. groupBy=\"<label>\" (e.g. level, app) returns a table per label value. "
                    + "groupBy=\"time\" returns counts per time bucket and marks spikes. Use this before reading lines. "
                    + "Example: query {app=\"backend\"} |= \"ERROR\", groupBy \"time\".",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public String countLogs(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = "LogQL log query starting with a stream selector, e.g. {app=\"backend\"} |= \"ERROR\"") String query,
            @McpToolParam(description = START, required = false) String start,
            @McpToolParam(description = END, required = false) String end,
            @McpToolParam(description = "A label name to break the count down by, or \"time\" for buckets over the window", required = false) String groupBy) {
        return service.count(connection, query, start, end, groupBy);
    }

    @McpTool(name = "queryMetrics",
            description = "Advanced: evaluate a metric LogQL expression over the window and print one table per series. "
                    + "Example: sum by (level) (count_over_time({app=\"backend\"}[5m])) or sum(rate({app=\"backend\"} |= \"timeout\"[1m])). "
                    + "For simple counts prefer countLogs, which writes the expression for you.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public String queryMetrics(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = "Metric LogQL expression, e.g. sum(rate({app=\"backend\"}[5m]))") String query,
            @McpToolParam(description = START, required = false) String start,
            @McpToolParam(description = END, required = false) String end,
            @McpToolParam(description = "Distance between points like \"30s\", \"5m\", \"1h\". Default: about 20 points over the window", required = false) String step) {
        return service.metrics(connection, query, start, end, step);
    }
}
