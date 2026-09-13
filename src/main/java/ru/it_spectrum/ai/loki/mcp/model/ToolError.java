package ru.it_spectrum.ai.loki.mcp.model;

/** Safe error payload. Only Loki query errors (400 / status=error) carry upstream text; it is the model's own query being rejected. */
public record ToolError(ErrorCode code, String message, boolean retryable) {
    /** Text shown to the model: one line, what went wrong and what to do. */
    public String text() { return "Error " + code + ": " + message; }
}
