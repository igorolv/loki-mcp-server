package ru.it_spectrum.ai.loki.mcp.service;

import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.client.LokiResponses;
import ru.it_spectrum.ai.loki.mcp.error.Errors;
import ru.it_spectrum.ai.loki.mcp.model.LogEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads one bounded Loki log page as chronologically ordered events.
 */
final class LogEventReader {
    private final LokiHttpClient client;

    LogEventReader(LokiHttpClient client) {
        this.client = client;
    }

    List<LogEvent> readPage(String connection, String query, Instant start, Instant end, int limit,
                            LokiHttpClient.Direction direction, Integer timeoutMs, String metricError) {
        var response = timeoutMs == null
                ? client.queryRange(connection, query, start, end, limit, direction, null)
                : client.queryRange(connection, query, start, end, limit, direction, null, timeoutMs);
        if (!(response.data() instanceof LokiResponses.Streams streams)) {
            throw Errors.invalid(metricError);
        }
        var events = new ArrayList<LogEvent>();
        for (var stream : streams.streams()) {
            for (var entry : stream.entries()) {
                events.add(new LogEvent(entry.timestampNanos(), stream.labels(), entry.line(),
                        entry.structuredMetadata()));
            }
        }
        events.sort(Comparator.comparingLong(LogEvent::nanos)); // Keep upstream order for identical timestamps.
        if (events.size() <= limit) return events;
        return new ArrayList<>(direction == LokiHttpClient.Direction.BACKWARD
                ? events.subList(events.size() - limit, events.size()) : events.subList(0, limit));
    }
}
