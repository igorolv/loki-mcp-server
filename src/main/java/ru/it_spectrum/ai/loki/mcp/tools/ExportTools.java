package ru.it_spectrum.ai.loki.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import ru.it_spectrum.ai.loki.mcp.service.ExportService;

import static ru.it_spectrum.ai.loki.mcp.tools.QueryTools.*;

/**
 * Registered only through the safe QueryToolsConfig wrapper. The only tool that writes, and only into export directories.
 */
public class ExportTools {
    private final ExportService service;

    public ExportTools(ExportService service) {
        this.service = service;
    }

    @McpTool(name = "exportLogs",
            description = "Save every matching log line of a window to a file on the local disk, oldest first and in full, only when "
                    + "the user asks to save or download logs. Filter like queryLogs: service=\"backend\", level=\"error\", text or a LogQL "
                    + "query, e.g. start=\"now-2h\". format=\"raw\" keeps the original lines, a layout name such as \"spring\" "
                    + "(listConnections names them) or a template like \"{time} {level:5} [{thread}] {logger} : {message}{stack}\" rewrites them. "
                    + "The answer gives the file path, the line count, and a start value to continue with when a limit stopped it.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = true))
    public String exportLogs(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = QUERY, required = false) String query,
            @McpToolParam(description = SERVICE, required = false) String service,
            @McpToolParam(description = LEVEL, required = false) String level,
            @McpToolParam(description = TEXT, required = false) String text,
            @McpToolParam(description = START, required = false) String start,
            @McpToolParam(description = "Window end. Default \"now\". Same formats as start.", required = false) String end,
            @McpToolParam(description = "\"raw\" (default), a layout name like \"spring\", or a template with {time}, {level}, {service}, "
                    + "{logger}, {message}, {stack} and line fields like {thread}", required = false) String format,
            @McpToolParam(description = "Directory to write into: leave it out for the default export directory, or a subdirectory "
                    + "name like \"incident-42\"; the user's configuration lists the allowed directories", required = false) String directory,
            @McpToolParam(description = "true writes one file per service into a new directory. Default false: one file", required = false) Boolean splitByService) {
        return this.service.export(connection, query, service, level, text, start, end, format, directory, splitByService);
    }
}
