package ru.it_spectrum.ai.loki.mcp.connection;

import ru.it_spectrum.ai.loki.mcp.service.Errors;

/**
 * Central transport/query defaults. Consumers enforce these as they are implemented. maxExportLines and
 * maxExportBytes bound one exportLogs call: it stops there and says where to continue.
 */
public record ConnectionLimits(int connectTimeoutMs, int requestTimeoutMs, int maxHttpResponseBytes,
                               int maxResponseBytes, int maxEntries, long maxIntervalSeconds,
                               int maxMetricSeries, int maxMetricPoints, int maxExportLines, long maxExportBytes) {
    public static final int MIN_RESPONSE_BYTES = 1024;
    public static final ConnectionLimits DEFAULTS = new ConnectionLimits(
            5_000, 30_000, 8 * 1024 * 1024, 64 * 1024, 1_000, 86_400);

    public ConnectionLimits(int connectTimeoutMs, int requestTimeoutMs, int maxHttpResponseBytes,
                            int maxResponseBytes, int maxEntries, long maxIntervalSeconds) {
        this(connectTimeoutMs, requestTimeoutMs, maxHttpResponseBytes, maxResponseBytes,
                maxEntries, maxIntervalSeconds, 100, 10_000);
    }

    public ConnectionLimits(int connectTimeoutMs, int requestTimeoutMs, int maxHttpResponseBytes,
                            int maxResponseBytes, int maxEntries, long maxIntervalSeconds,
                            int maxMetricSeries, int maxMetricPoints) {
        this(connectTimeoutMs, requestTimeoutMs, maxHttpResponseBytes, maxResponseBytes, maxEntries, maxIntervalSeconds,
                maxMetricSeries, maxMetricPoints, 500_000, 256L * 1024 * 1024);
    }

    public ConnectionLimits {
        if (connectTimeoutMs <= 0 || requestTimeoutMs <= 0 || maxHttpResponseBytes <= 0
                || maxResponseBytes < MIN_RESPONSE_BYTES || maxEntries <= 0 || maxIntervalSeconds <= 0
                || maxMetricSeries <= 0 || maxMetricPoints <= 0 || maxExportLines <= 0 || maxExportBytes <= 0) {
            throw Errors.configuration();
        }
    }
}
