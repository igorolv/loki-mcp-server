package ru.it_spectrum.ai.loki.mcp.service;

import ru.it_spectrum.ai.loki.mcp.model.ToolError;

/** Contains only a controlled message; original exceptions may contain credentials. */
public final class LokiOperationException extends RuntimeException {
    private final ToolError error;

    public LokiOperationException(ToolError error) {
        super(error.message());
        this.error = error;
    }

    public ToolError error() {
        return error;
    }
}
