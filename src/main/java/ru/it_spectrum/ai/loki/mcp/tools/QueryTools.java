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
    static final String QUERY = "LogQL log query starting with a stream selector, e.g. {app=\"backend\"} |= \"ERROR\". "
            + "Leave it out when you pass service, level or text.";
    static final String SERVICE = "Instead of query: service name as discoverLogs prints it, e.g. \"backend\"; several: \"backend,frontend\"";
    static final String LEVEL = "Instead of query: \"error\" or \"warn\" (listConnections names the levels of the stand)";
    static final String TEXT = "Instead of query: text the line must contain, case-sensitive, e.g. \"timeout\"";
    private final QueryService service;

    public QueryTools(QueryService service) {
        this.service = service;
    }

    @McpTool(name = "queryLogs",
            description = "Read log lines, newest first within the window: pass service=\"backend\", level=\"error\" (and text) "
                    + "or a LogQL query. Each line is printed as "
                    + "'time LEVEL service message' with stack traces shortened. Example query: {app=\"backend\"} |= \"ERROR\"; "
                    + "with JSON logs add | json | log_level=~\"(?i)error\". To find lines of one request use |= \"<traceId>\". "
                    + "If the footer says there are more lines, either narrow the query or repeat with the end value it gives. "
                    + "Use raw=true with a narrow query to see the complete original line with its stream labels.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public String queryLogs(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = QUERY, required = false) String query,
            @McpToolParam(description = SERVICE, required = false) String service,
            @McpToolParam(description = LEVEL, required = false) String level,
            @McpToolParam(description = TEXT, required = false) String text,
            @McpToolParam(description = START, required = false) String start,
            @McpToolParam(description = END, required = false) String end,
            @McpToolParam(description = "Maximum lines to return, default 50", required = false) Integer limit,
            @McpToolParam(description = "true prints original lines unchanged (full JSON, full stack trace) with their stream labels. Default false", required = false) Boolean raw) {
        return this.service.logs(connection, query, service, level, text, start, end, limit, raw);
    }

    @McpTool(name = "summarizeLogs",
            description = "Summarize many log lines instead of reading them: groups of errors by root cause and of other lines by "
                    + "message, with counts in the sample, the causes the connection's rules know, noise apart, and restarts or deploys "
                    + "in the window. Use it when asked what is broken or when countLogs shows hundreds of lines, e.g. level=\"error\", "
                    + "start=\"now-4h\" (or a service, or a LogQL query). Then read one group with queryLogs and its text, or "
                    + "getLogContext around its time.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public String summarizeLogs(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = QUERY, required = false) String query,
            @McpToolParam(description = SERVICE, required = false) String service,
            @McpToolParam(description = LEVEL, required = false) String level,
            @McpToolParam(description = TEXT, required = false) String text,
            @McpToolParam(description = START, required = false) String start,
            @McpToolParam(description = END, required = false) String end,
            @McpToolParam(description = "How many newest lines to sample, default 500", required = false) Integer sample) {
        return this.service.summarize(connection, query, service, level, text, start, end, sample);
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
            description = "Count log lines (service/level/text or a LogQL query), to check whether a problem exists, how big it is and when it started. "
                    + "Without groupBy returns one number. groupBy=\"<label>\" (e.g. level, app) returns a table per label value. "
                    + "groupBy=\"time\" returns counts per time bucket and marks spikes. Use this before reading lines. "
                    + "Example: service=\"backend\", level=\"error\", groupBy=\"time\".",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public String countLogs(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = QUERY, required = false) String query,
            @McpToolParam(description = SERVICE, required = false) String service,
            @McpToolParam(description = LEVEL, required = false) String level,
            @McpToolParam(description = TEXT, required = false) String text,
            @McpToolParam(description = START, required = false) String start,
            @McpToolParam(description = END, required = false) String end,
            @McpToolParam(description = "A label name to break the count down by, or \"time\" for buckets over the window", required = false) String groupBy) {
        return this.service.count(connection, query, service, level, text, start, end, groupBy);
    }
}
