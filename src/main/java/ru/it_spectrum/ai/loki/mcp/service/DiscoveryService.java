package ru.it_spectrum.ai.loki.mcp.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.client.LokiResponses.SeriesResponse;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionDefinition;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static ru.it_spectrum.ai.loki.mcp.service.LogText.ENVELOPE_BYTES;
import static ru.it_spectrum.ai.loki.mcp.service.LogText.assemble;
import static ru.it_spectrum.ai.loki.mcp.service.LogText.bytes;
import static ru.it_spectrum.ai.loki.mcp.service.LogText.truncate;
import static ru.it_spectrum.ai.loki.mcp.service.LogText.window;

/**
 * Lists label names, values or stream label sets for a time window, without sampling log lines.
 */
@Service
public class DiscoveryService {
    private static final int MAX_NAMES = 100;
    private static final int MAX_VALUES = 200;
    private static final int MAX_SERIES = 50;
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

    public String discover(String connection, String start, String end, String label, String match) {
        var definition = registry.require(connection);
        String selector = match == null ? null : match.strip();
        if (selector != null && (selector.length() < 3 || !selector.startsWith("{") || !selector.endsWith("}")
                || selector.substring(1, selector.length() - 1).isBlank())) {
            throw Errors.invalid("match must be a non-empty LogQL stream selector, e.g. {app=\"api\"}; no line filters.");
        }
        var range = QueryTime.range(start, end, clock.instant(), definition.timezone(),
                selector == null ? definition.limits().maxDiscoveryIntervalSeconds()
                        : definition.limits().maxSeriesIntervalSeconds());
        String requested = label == null || label.isBlank() ? null : label.strip();
        if (requested != null && !requested.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw Errors.invalid("label must be a label name, e.g. \"app\".");
        }
        if (selector != null) return discoverSeries(definition, range, requested, selector);
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

    private String discoverSeries(ConnectionDefinition definition, QueryTime.Range range, String label, String match) {
        SeriesResponse response;
        try {
            response = client.series(definition.name(), match, range.start(), range.end());
        } catch (LokiOperationException failure) {
            if (failure.error().code() == ErrorCode.UPSTREAM_RESPONSE_TOO_LARGE) {
                throw Errors.failure(ErrorCode.UPSTREAM_RESPONSE_TOO_LARGE,
                        "Loki series response exceeds maxHttpResponseBytes; no complete result is available. "
                                + "Narrow match or the time window.");
            }
            if (failure.error().code() == ErrorCode.UPSTREAM_TIMEOUT) {
                throw Errors.failure(ErrorCode.UPSTREAM_TIMEOUT,
                        "Loki series request timed out. Narrow match or the time window.");
            }
            throw failure;
        }
        if (label == null) return seriesText(definition, range, response);
        return scopedValuesText(definition, range, label, response);
    }

    private String seriesText(ConnectionDefinition definition, QueryTime.Range range, SeriesResponse response) {
        var rows = new TreeSet<String>();
        for (Map<String, String> labels : response.labelSets()) rows.add(labelSet(labels));
        String header = "Series — " + definition.name() + ", " + window(range, definition.timezone())
                + ": " + rows.size() + " label sets returned by Loki.";
        return boundedSeriesText(definition, header, rows, MAX_SERIES, "label sets returned by Loki",
                "Label sets do not count log lines; use countLogs or queryLogs to check lines in the window.");
    }

    private String scopedValuesText(ConnectionDefinition definition, QueryTime.Range range, String label,
                                    SeriesResponse response) {
        var values = new TreeSet<String>();
        for (Map<String, String> labels : response.labelSets()) {
            if (labels.containsKey(label)) values.add(quote(labels.get(label)));
        }
        String header = "Values of " + label + " among series — " + definition.name() + ", "
                + window(range, definition.timezone()) + ": " + values.size() + " values from "
                + response.labelSets().size() + " label sets returned by Loki.";
        return boundedSeriesText(definition, header, values, MAX_VALUES, "values found among the returned label sets",
                "These values describe stream labels, not log-line counts; use countLogs or queryLogs to check lines.");
    }

    private String boundedSeriesText(ConnectionDefinition definition, String header, TreeSet<String> rows,
                                     int maximum, String unit, String completeFooter) {
        var lines = new ArrayList<>(rows.stream().limit(maximum).toList());
        int budget = definition.limits().maxResponseBytes() - ENVELOPE_BYTES;
        while (true) {
            int hidden = rows.size() - lines.size();
            String footer = hidden > 0 ? "Output limit reached: showing " + lines.size() + " of " + rows.size()
                    + " " + unit + ". Narrow match or the time window." : completeFooter;
            String text = assemble(header, lines, footer);
            if (bytes(text) <= budget) return text;
            if (lines.isEmpty()) throw Errors.failure(ErrorCode.RESPONSE_BUDGET_EXCEEDED,
                    "Series output does not fit maxResponseBytes of this connection. Raise the limit.");
            lines.removeLast();
        }
    }

    private String labelSet(Map<String, String> labels) {
        var parts = new ArrayList<String>();
        for (var entry : new TreeMap<>(labels).entrySet()) parts.add(escape(entry.getKey()) + "=" + quote(entry.getValue()));
        return "{" + String.join(", ", parts) + "}";
    }

    private String quote(String value) {
        return "\"" + escape(value) + "\"";
    }

    private String escape(String value) {
        var text = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') text.append('\\').append(c);
            else if (c == '\n') text.append("\\n");
            else if (c == '\r') text.append("\\r");
            else if (c == '\t') text.append("\\t");
            else if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
                    || Character.getType(c) == Character.LINE_SEPARATOR
                    || Character.getType(c) == Character.PARAGRAPH_SEPARATOR)
                text.append("\\u").append(String.format("%04x", (int) c));
            else text.append(c);
        }
        return text.toString();
    }
}
