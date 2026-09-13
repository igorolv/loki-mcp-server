package ru.it_spectrum.ai.loki.mcp.model;

import java.util.Map;

/** One query-output entry as returned by Loki: labels of the result stream, the line and structured metadata. */
public record LogEvent(String timestampNanos, Map<String, String> labels, String line, Map<String, String> structuredMetadata) {
    public LogEvent { labels = Map.copyOf(labels); structuredMetadata = Map.copyOf(structuredMetadata); }
    public long nanos() { return Long.parseLong(timestampNanos); }
}
