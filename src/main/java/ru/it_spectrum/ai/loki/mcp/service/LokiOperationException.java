package ru.it_spectrum.ai.loki.mcp.service;

import ru.it_spectrum.ai.loki.mcp.model.ToolError;

/**
 * Contains only a controlled message; original exceptions may contain credentials. The message is the text shown to the
 * model: Spring AI turns an exception of a tool into an error result with its message.
 */
public final class LokiOperationException extends RuntimeException {
    private final ToolError error;

    public LokiOperationException(ToolError error) {
        super(error.text());
        this.error = error;
    }

    public ToolError error() {
        return error;
    }
}
