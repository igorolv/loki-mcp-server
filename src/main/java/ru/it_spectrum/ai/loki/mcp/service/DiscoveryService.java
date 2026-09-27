package ru.it_spectrum.ai.loki.mcp.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;

import java.time.Clock;
import java.util.ArrayList;
import java.util.TreeSet;

import static ru.it_spectrum.ai.loki.mcp.service.LogText.ENVELOPE_BYTES;
import static ru.it_spectrum.ai.loki.mcp.service.LogText.assemble;
import static ru.it_spectrum.ai.loki.mcp.service.LogText.bytes;
import static ru.it_spectrum.ai.loki.mcp.service.LogText.truncate;
import static ru.it_spectrum.ai.loki.mcp.service.LogText.window;

/**
 * Lists label names or values for a time window, without sampling log lines.
 */
@Service
public class DiscoveryService {
    private static final int MAX_NAMES = 100;
    private static final int MAX_VALUES = 200;
    private final ConnectionRegistry registry;
    private final LokiHttpClient client;
    private final Clock clock;

    @Autowired
    public DiscoveryService(ConnectionRegistry registry, LokiHttpClient client) {
        this(registry, client, Clock.systemUTC());
    }

    public DiscoveryService(ConnectionRegistry registry, LokiHttpClient client, Clock clock) {
        this.registry = registry;
        this.client = client;
        this.clock = clock;
    }

    public String discover(String connection, String start, String end, String label) {
        var definition = registry.require(connection);
        var range = QueryTime.range(start, end, clock.instant(), definition.timezone(),
                definition.limits().maxDiscoveryIntervalSeconds());
        String requested = label == null || label.isBlank() ? null : label.strip();
        if (requested != null && !requested.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw Errors.invalid("label must be a label name, e.g. \"app\".");
        }
        var values = new TreeSet<>(requested == null
                ? client.labels(connection, range.start(), range.end()).values()
                : client.labelValues(connection, requested, range.start(), range.end()).values());
        String header = (requested == null ? "Labels" : "Values of " + requested)
                + " — " + connection + ", " + window(range, definition.timezone()) + ": " + values.size() + ".";
        var lines = new ArrayList<>(values.stream().limit(requested == null ? MAX_NAMES : MAX_VALUES)
                .map(value -> truncate(value, 200)).toList());
        int budget = definition.limits().maxResponseBytes() - ENVELOPE_BYTES;
        while (true) {
            int hidden = values.size() - lines.size();
            String footer = hidden > 0 ? "(+" + hidden + " more; only the first values are shown)"
                    : requested == null ? "Use label=\"<name>\" to list its values; use queryLogs with a LogQL selector to read lines." : "";
            String text = assemble(header, lines, footer);
            if (bytes(text) <= budget) return text;
            if (lines.isEmpty()) throw Errors.failure(ErrorCode.RESPONSE_BUDGET_EXCEEDED,
                    "Label output does not fit maxResponseBytes of this connection. Raise the limit.");
            lines.removeLast();
        }
    }
}
