package ru.it_spectrum.ai.loki.mcp.connection;

import ru.it_spectrum.ai.loki.mcp.error.Errors;

/**
 * Central transport/query defaults. Consumers enforce these as they are implemented.
 */
public record ConnectionLimits(int connectTimeoutMs, int requestTimeoutMs, int maxHttpResponseBytes,
                               int maxResponseBytes, int maxEntries, long maxIntervalSeconds,
                               int maxExportLines, long maxExportBytes,
                               long maxCountIntervalSeconds, long maxTimeCountIntervalSeconds,
                               long maxDiscoveryIntervalSeconds, long maxSeriesIntervalSeconds,
                               int maxExportDurationMs) {
    public static final int MIN_RESPONSE_BYTES = 1024;
    public static final int DEFAULT_EXPORT_DURATION_MS = 25_000;
    public static final ConnectionLimits DEFAULTS = new ConnectionLimits(
            5_000, 30_000, 8 * 1024 * 1024, 64 * 1024, 1_000, 86_400,
            500_000, 256L * 1024 * 1024, 86_400, 604_800, 604_800, 86_400, DEFAULT_EXPORT_DURATION_MS);

    public ConnectionLimits(int connectTimeoutMs, int requestTimeoutMs, int maxHttpResponseBytes,
                            int maxResponseBytes, int maxEntries, long maxIntervalSeconds) {
        this(connectTimeoutMs, requestTimeoutMs, maxHttpResponseBytes, maxResponseBytes, maxEntries, maxIntervalSeconds,
                500_000, 256L * 1024 * 1024, maxIntervalSeconds, maxIntervalSeconds,
                maxIntervalSeconds, maxIntervalSeconds, DEFAULT_EXPORT_DURATION_MS);
    }

    public ConnectionLimits(int connectTimeoutMs, int requestTimeoutMs, int maxHttpResponseBytes,
                            int maxResponseBytes, int maxEntries, long maxIntervalSeconds,
                            int maxExportLines, long maxExportBytes) {
        this(connectTimeoutMs, requestTimeoutMs, maxHttpResponseBytes, maxResponseBytes, maxEntries, maxIntervalSeconds,
                maxExportLines, maxExportBytes, maxIntervalSeconds, maxIntervalSeconds,
                maxIntervalSeconds, maxIntervalSeconds, DEFAULT_EXPORT_DURATION_MS);
    }

    public ConnectionLimits(int connectTimeoutMs, int requestTimeoutMs, int maxHttpResponseBytes,
                            int maxResponseBytes, int maxEntries, long maxIntervalSeconds,
                            int maxExportLines, long maxExportBytes,
                            long maxCountIntervalSeconds, long maxTimeCountIntervalSeconds,
                            long maxDiscoveryIntervalSeconds, int maxExportDurationMs) {
        this(connectTimeoutMs, requestTimeoutMs, maxHttpResponseBytes, maxResponseBytes, maxEntries, maxIntervalSeconds,
                maxExportLines, maxExportBytes, maxCountIntervalSeconds, maxTimeCountIntervalSeconds,
                maxDiscoveryIntervalSeconds, maxIntervalSeconds, maxExportDurationMs);
    }

    public ConnectionLimits {
        if (connectTimeoutMs <= 0 || requestTimeoutMs <= 0 || maxHttpResponseBytes <= 0
                || maxResponseBytes < MIN_RESPONSE_BYTES || maxEntries <= 0 || maxIntervalSeconds <= 0
                || maxExportLines <= 0 || maxExportBytes <= 0
                || maxCountIntervalSeconds <= 0 || maxTimeCountIntervalSeconds <= 0
                || maxDiscoveryIntervalSeconds <= 0 || maxSeriesIntervalSeconds <= 0 || maxExportDurationMs <= 0) {
            throw Errors.configuration();
        }
    }
}
