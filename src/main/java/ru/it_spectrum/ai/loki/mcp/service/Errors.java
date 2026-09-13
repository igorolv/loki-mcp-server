package ru.it_spectrum.ai.loki.mcp.service;

import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;
import ru.it_spectrum.ai.loki.mcp.model.ToolError;

public final class Errors {
    private Errors() {
    }

    public static LokiOperationException configuration() {
        return failure(ErrorCode.CONFIGURATION_ERROR,
                "Cannot load connections. Check connections.json or LOKI_MCP_CONNECTIONS_FILE against the configuration example.");
    }

    public static LokiOperationException failure(ErrorCode code, String safeMessage) {
        return new LokiOperationException(new ToolError(code, safeMessage, false));
    }

    /**
     * Argument problems say what to change; they never echo secrets, only the model's own values.
     */
    public static LokiOperationException invalid(String whatToDo) {
        return failure(ErrorCode.INVALID_ARGUMENT, whatToDo);
    }

    public static ToolError from(Exception exception) {
        if (exception instanceof LokiOperationException known) {
            return known.error();
        }
        return new ToolError(ErrorCode.INTERNAL_ERROR, "Operation failed internally.", false);
    }
}
