package ru.it_spectrum.ai.loki.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import ru.it_spectrum.ai.loki.mcp.service.QueryService;

/**
 * Registered through QueryToolsConfig, which turns every failure into a short safe text error.
 */
public class QueryTools {
    static final String START = "Window start. Default \"now-1h\". Examples: \"now-15m\", \"now-2d\", \"2026-09-13T10:00:00+03:00\".";
    static final String END = "Window end. Default \"now\". Same formats as start; use the value from a previous footer to read older lines.";
    private final QueryService service;

    public QueryTools(QueryService service) {
        this.service = service;
    }

    @McpTool(name = "queryLogs",
            description = "Read log lines matching a LogQL log query, newest first within the window. Each line is printed as "
                    + "'time LEVEL service message' with stack traces shortened. Example query: {app=\"backend\"} |= \"ERROR\"; "
                    + "with JSON logs add | json | log_level=~\"(?i)error\". To find lines of one request use |= \"<traceId>\". "
                    + "If the footer says there are more lines, either narrow the query or repeat with the end value it gives. "
                    + "Use raw=true with a narrow query to see the complete original line with its stream labels.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public String queryLogs(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = "LogQL log query starting with a stream selector, e.g. {app=\"backend\"} |= \"ERROR\"") String query,
            @McpToolParam(description = START, required = false) String start,
            @McpToolParam(description = END, required = false) String end,
            @McpToolParam(description = "Maximum lines to return, default 50", required = false) Integer limit,
            @McpToolParam(description = "true prints original lines unchanged (full JSON, full stack trace) with their stream labels. Default false", required = false) Boolean raw) {
        return service.logs(connection, query, start, end, limit, raw);
    }

    @McpTool(name = "summarizeLogs",
            description = "Summarize many matching log lines instead of reading them: errors grouped by root cause (the exception, "
                    + "its wrappers, the line of our code), other lines by repeated message, each group with its count in the sample, "
                    + "first/last time and newest example; rare one-off messages listed separately. Each group says whether it is new, "
                    + "more than usual or seen in the 7 days before; restarts and deploys are listed on top. Use it when countLogs shows "
                    + "hundreds of lines, e.g. query {app=\"backend\"} |= \"ERROR\"; counts cover the sampled newest lines only. "
                    + "Then read one group with queryLogs |= \"<part of its message>\" or getLogContext around its time.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public String summarizeLogs(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = "LogQL log query starting with a stream selector, e.g. {app=\"backend\"} |= \"ERROR\"") String query,
            @McpToolParam(description = START, required = false) String start,
            @McpToolParam(description = END, required = false) String end,
            @McpToolParam(description = "How many newest lines to sample, default 500", required = false) Integer sample) {
        return service.summarize(connection, query, start, end, sample);
    }

    @McpTool(name = "followKey",
            description = "Follow one identifier across services: every line of the selector that holds the key, oldest first, "
                    + "with the root cause of errors. Use it for a key that summarizeLogs prints after 'linked:', or an id you were given "
                    + "(taskExecutionId=13548, ErrorID ERR-..., a trace id). Pass the selector of the whole environment, e.g. {namespace=\"dev\"}, "
                    + "and a window around the time the key was seen. The value is matched as a whole word, so 13548 does not match 135480.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public String followKey(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = "Stream selector only, covering every service to search, e.g. {namespace=\"dev\"}") String selector,
            @McpToolParam(description = "The key as printed, e.g. \"taskExecutionId=13548\", or a bare value, e.g. \"ERR-5ced1eb2-849d-478d-8cbe-31ad23a1f88a\"") String key,
            @McpToolParam(description = START, required = false) String start,
            @McpToolParam(description = END, required = false) String end,
            @McpToolParam(description = "Maximum lines to return, oldest first, default 100", required = false) Integer limit) {
        return service.followKey(connection, selector, key, start, end, limit);
    }

    @McpTool(name = "getLogContext",
            description = "Read what happened around one log line in its stream: a number of lines before it and after it, "
                    + "regardless of how busy the service is. Use it after queryLogs found an interesting line: pass the stream selector "
                    + "(the braces part only, e.g. {app=\"backend\"}, without |= filters, so that lines that do not match the filter are shown too) "
                    + "and the time printed for the line (e.g. \"10:12:03.123\"). Lines at that time are marked with >>>. "
                    + "The footer tells how to read further back or forward.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public String getLogContext(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = "Stream selector only, e.g. {app=\"backend\"}; no filters or | json") String selector,
            @McpToolParam(description = "Time of the line: \"10:12:03.123\" as printed by queryLogs (today or the nearest past day), "
                    + "or \"2026-09-13T10:12:03.123+03:00\"") String time,
            @McpToolParam(description = "Lines to show before that time, default 20", required = false) Integer before,
            @McpToolParam(description = "Lines to show after that time, default 20", required = false) Integer after) {
        return service.context(connection, selector, time, before, after);
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
