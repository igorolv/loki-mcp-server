package ru.it_spectrum.ai.loki.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import ru.it_spectrum.ai.loki.mcp.service.ExportService;

import static ru.it_spectrum.ai.loki.mcp.tools.QueryTools.QUERY;
import static ru.it_spectrum.ai.loki.mcp.tools.QueryTools.START;

/**
 * The only tool that writes, to a new file in the requested local directory.
 */
@Component
public class ExportTools {
    private final ExportService service;

    public ExportTools(ExportService service) {
        this.service = service;
    }

    @McpTool(name = "exportLogs",
            description = "Save matching log lines of a window to a local file, oldest first, when the user asks to save logs. "
                    + "Pass the same LogQL query as queryLogs, e.g. {app=\"backend\"} |= \"ERROR\". "
                    + "format=\"raw\" keeps the lines returned by Loki, including any LogQL line_format stage; "
                    + "a template like \"{time} {level:5} [{thread}] {logger} : {message}{stack}\" rewrites them locally. "
                    + "Pass a user-specified directory directly; omit it for the default export directory. "
                    + "The answer gives the file path, the line count, and a start value to continue with when a limit stopped it.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = true))
    public String exportLogs(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = QUERY) String query,
            @McpToolParam(description = START, required = false) String start,
            @McpToolParam(description = "Window end. Default \"now\". Same formats as start.", required = false) String end,
            @McpToolParam(description = "\"raw\" (default), or a template with {time}, {level}, {service}, "
                    + "{logger}, {message}, {stack} and line fields like {thread}", required = false) String format,
            @McpToolParam(description = "Local directory for the new file, e.g. C:\\tmp\\logs or incident-42. "
                    + "Omit it for the default export directory; a relative path is resolved under that directory. "
                    + "Configured exportRoots, if present, restrict destinations.", required = false)
            String directory) {
        return this.service.export(connection, query, start, end, format, directory);
    }
}
