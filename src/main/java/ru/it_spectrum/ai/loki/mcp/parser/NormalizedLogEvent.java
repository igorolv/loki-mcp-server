package ru.it_spectrum.ai.loki.mcp.parser;

import ru.it_spectrum.ai.loki.mcp.model.LogEvent;

import java.util.Map;
import java.util.Objects;

/**
 * One Loki event and the fields parsed from its line, labels and structured metadata.
 */
public record NormalizedLogEvent(LogEvent source, Format format, String level, String service, String logger,
                                 String message, String traceId, String stackTrace, Map<String, String> fields) {
    public NormalizedLogEvent {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(message, "message");
        fields = Map.copyOf(fields);
    }

    public enum Format {JSON, PLAIN}
}
