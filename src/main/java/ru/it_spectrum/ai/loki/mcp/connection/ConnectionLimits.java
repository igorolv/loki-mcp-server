package ru.it_spectrum.ai.loki.mcp.connection;

import ru.it_spectrum.ai.loki.mcp.service.Errors;

/** Central transport/query defaults. Consumers enforce these as they are implemented. */
public record ConnectionLimits(int connectTimeoutMs, int requestTimeoutMs, int maxHttpResponseBytes,
                               int maxResponseBytes, int maxEntries, long maxIntervalSeconds) {
    public static final ConnectionLimits DEFAULTS = new ConnectionLimits(
            5_000, 30_000, 8 * 1024 * 1024, 64 * 1024, 1_000, 86_400);

    public ConnectionLimits {
        if (connectTimeoutMs <= 0 || requestTimeoutMs <= 0 || maxHttpResponseBytes <= 0
                || maxResponseBytes < 1024 || maxEntries <= 0 || maxIntervalSeconds <= 0) {
            throw Errors.configuration();
        }
    }
}
