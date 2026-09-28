package ru.it_spectrum.ai.loki.mcp.parser;

import java.util.Map;

/**
 * Normalized fields and parsed scalar values of one Loki event. The original timestamp, labels, metadata and line
 * remain in LogEvent.
 */
public record NormalizedLogLine(Format format, String level, String service, String logger, String message,
                                String traceId, String stackTrace, Map<String, String> fields) {
    public NormalizedLogLine {
        fields = Map.copyOf(fields);
    }

    public enum Format {JSON, PLAIN}
}
