package ru.it_spectrum.ai.loki.mcp.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.client.LokiResponses;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionDefinition;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;
import ru.it_spectrum.ai.loki.mcp.model.LogEvent;

import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;

import static ru.it_spectrum.ai.loki.mcp.service.DiscoveryLimits.*;
import static ru.it_spectrum.ai.loki.mcp.service.LogText.*;

/**
 * Labels and values of a scope, line format and JSON fields from a small sample, and a ready-to-use selector.
 */
@Service
public class DiscoveryService {
    // A selector only, never a pipeline: this scope is reused for series lookup and for sampling lines.
    public static final String MATCHER = "[a-zA-Z_][a-zA-Z0-9_]*\\s*(?:=~|!~|!=|=)\\s*\"(?:[^\"\\\\\\r\\n]|\\\\[^\\r\\n])*+\"";
    public static final Pattern SELECTOR = Pattern.compile("\\s*\\{\\s*" + MATCHER + "(?:\\s*,\\s*" + MATCHER + ")*+\\s*}\\s*");
    private static final List<String> LEVEL_FIELDS = List.of("log_level", "level", "severity", "lvl");
    private final ConnectionRegistry registry;
    private final LokiHttpClient client;
    private final Clock clock;
    private final java.util.concurrent.ConcurrentHashMap<String, EventNormalizer> normalizers = new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    public DiscoveryService(ConnectionRegistry registry, LokiHttpClient client) {
        this(registry, client, Clock.systemUTC());
    }

    public DiscoveryService(ConnectionRegistry registry, LokiHttpClient client, Clock clock) {
        this.registry = registry;
        this.client = client;
        this.clock = clock;
    }

    private static void appendLabels(StringBuilder text, Map<String, Set<String>> labels) {
        if (labels.isEmpty()) {
            text.append("\nNo labels found in this window.");
            return;
        }
        text.append("\nLabels:");
        for (var label : labels.entrySet()) {
            var values = label.getValue();
            text.append("\n  ").append(label.getKey()).append(": ");
            if (values.isEmpty()) text.append("(values not available)");
            else if (values.size() > VALUES_PER_LABEL)
                text.append(values.size()).append(" distinct values (high cardinality, not listed)");
            else {
                var shown = values.stream().limit(VALUES_LISTED).map(v -> truncate(v, 60)).toList();
                text.append(String.join(", ", shown));
                if (values.size() > shown.size()) text.append(" (+").append(values.size() - shown.size())
                        .append(" more: discoverLogs with label=\"").append(label.getKey()).append("\")");
            }
        }
    }

    private static void appendSample(StringBuilder text, Sample sample, int exampleChars) {
        if (sample.events().isEmpty()) {
            text.append("\nNo lines sampled in this window; fields are unknown.");
            return;
        }
        int total = sample.events().size();
        text.append("\nLine format (").append(total).append(" newest lines sampled): ");
        var parts = new ArrayList<String>();
        if (sample.json() > 0) parts.add("JSON " + sample.json());
        if (sample.plain() > 0) parts.add("plain text " + sample.plain());
        text.append(String.join(", ", parts)).append('.');
        if (!sample.levels().isEmpty())
            text.append("\nLevels seen: ").append(String.join(", ", sample.levels())).append('.');
        if (!sample.fields().isEmpty()) {
            var names = sample.fields().entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .map(Map.Entry::getKey).limit(FIELDS_LISTED).toList();
            text.append("\nJSON fields (after | json): ").append(String.join(", ", names));
            if (sample.fields().size() > names.size())
                text.append(" (+").append(sample.fields().size() - names.size()).append(" more)");
            text.append('.');
        }
        if (exampleChars > 0)
            text.append("\nExample line: ").append(truncate(sample.events().getFirst().line().replace('\n', ' '), exampleChars));
    }

    private static void appendNext(StringBuilder text, String scope, Map<String, Set<String>> labels, Sample sample, ConnectionDefinition definition) {
        text.append("\nNext: use countLogs or queryLogs with a selector like ");
        String serviceLabel = definition.serviceLabels().stream().filter(l -> labels.containsKey(l) && !labels.get(l).isEmpty()).findFirst().orElse(null);
        boolean scoped = scope != null && !scope.startsWith("{" + serviceLabel + "=~\".+\"}")
                && Pattern.compile("[{,]\\s*" + Pattern.quote(String.valueOf(serviceLabel)) + "\\s*(=|=~|!=|!~)").matcher(scope).find();
        if (serviceLabel != null && !scoped) {
            String value = labels.get(serviceLabel).iterator().next();
            String base = scope == null || scope.startsWith("{" + serviceLabel + "=~") ? "" : scope.strip().replaceAll("^\\{|}$", "") + ", ";
            text.append('{').append(base).append(serviceLabel).append("=\"").append(value).append("\"}");
        } else text.append(scope == null ? "{<label>=\"<value>\"}" : scope);
        String levelField = LEVEL_FIELDS.stream().filter(sample.fields()::containsKey).findFirst().orElse(null);
        if (levelField != null)
            text.append("; filter JSON fields with | json, e.g. | json | ").append(levelField).append("=~\"(?i)error\"");
        else if (labels.containsKey("level")) text.append("; filter by the level label, e.g. {..., level=\"error\"}");
        text.append("; filter text with |= \"substring\".");
    }

    /**
     * The normalizer of a connection, which knows the plain-text line formats of its rules catalogue.
     */
    private EventNormalizer normalizer(ru.it_spectrum.ai.loki.mcp.connection.ConnectionDefinition definition) {
        return normalizers.computeIfAbsent(definition.name(), name -> new EventNormalizer(definition.formats()));
    }

    public String discover(String connection, String selector, String start, String end, String label) {
        var definition = registry.require(connection);
        var window = QueryTime.range(start, end, clock.instant(), definition.timezone(), definition.limits().maxIntervalSeconds());
        boolean scoped = selector != null && !selector.isBlank();
        if (scoped && (selector.length() > SELECTOR_CHARACTERS || !SELECTOR.matcher(selector).matches()))
            throw Errors.invalid("selector must be a stream selector only, like {app=\"backend\"} with double-quoted values; no filters or pipelines.");
        if (label != null && !label.isBlank())
            return values(connection, scoped ? selector.strip() : null, window, definition, label.strip());
        var text = new StringBuilder();
        var labels = new TreeMap<String, Set<String>>();
        String scope;
        if (!scoped) {
            scope = labelsOverview(connection, window, definition, labels, text);
        } else {
            scope = selector.strip();
            var series = client.series(connection, List.of(scope), window.start(), window.end()).streams();
            text.append("Streams matching ").append(scope).append(" in ").append(window(window, definition.timezone())).append(": ").append(series.size()).append('.');
            for (var stream : series.subList(0, Math.min(series.size(), SERIES)))
                for (var pair : stream.entrySet())
                    labels.computeIfAbsent(pair.getKey(), ignored -> new TreeSet<>()).add(pair.getValue());
            if (series.size() > SERIES)
                text.append(" Labels below come from the first ").append(SERIES).append(" streams.");
            appendLabels(text, labels);
        }
        var sample = sample(connection, scope, window, definition);
        int budget = definition.limits().maxResponseBytes() - ENVELOPE_BYTES;
        // Degrade gracefully: a shorter example first, then no example, before giving up.
        for (int exampleChars : new int[]{EXAMPLE_CHARS, EXAMPLE_CHARS / 4, 0}) {
            var candidate = new StringBuilder(text);
            appendSample(candidate, sample, exampleChars);
            appendNext(candidate, scope, labels, sample, definition);
            if (bytes(candidate) <= budget) return candidate.toString();
        }
        throw Errors.failure(ErrorCode.RESPONSE_BUDGET_EXCEEDED,
                "Discovery output does not fit maxResponseBytes of this connection. Use a narrower selector or raise the limit.");
    }

    /**
     * All values of one label, one per line: the overview lists only a few and the model needs the rest, e.g. every service.
     */
    private String values(String connection, String selector, QueryTime.Range window, ConnectionDefinition definition, String label) {
        if (!label.matches("[a-zA-Z_][a-zA-Z0-9_]*"))
            throw Errors.invalid("label must be a label name (letters, digits, underscore), e.g. \"applicationName\"; see the names in discoverLogs without label.");
        var values = new TreeSet<>(client.labelValues(connection, label, window.start(), window.end(), selector).values());
        String header = "Values of " + label + (selector == null ? "" : " in streams matching " + selector) + ", "
                + window(window, definition.timezone()) + " (" + connection + "): " + values.size() + ".";
        if (values.isEmpty())
            return header + "\nNo values in this window; check the label name with discoverLogs without label, or widen start/end.";
        var lines = new ArrayList<>(values.stream().limit(LABEL_VALUES).map(v -> truncate(v, 200)).toList());
        int budget = definition.limits().maxResponseBytes() - ENVELOPE_BYTES;
        // Alphabetical order is the contract, so the tail is cut rather than the head.
        while (true) {
            int hidden = values.size() - lines.size();
            String text = assemble(header, lines, hidden > 0 ? "(+" + hidden + " more; narrow with selector)" : "");
            if (bytes(text) <= budget) return text;
            if (lines.isEmpty()) throw Errors.failure(ErrorCode.RESPONSE_BUDGET_EXCEEDED,
                    "Label values do not fit maxResponseBytes of this connection. Narrow with selector or raise the limit.");
            lines.removeLast();
        }
    }

    /**
     * Without a selector: label names from /labels, values per label from /label/{name}/values.
     */
    private String labelsOverview(String connection, QueryTime.Range window, ConnectionDefinition definition,
                                  Map<String, Set<String>> labels, StringBuilder text) {
        var names = client.labels(connection, window.start(), window.end(), null).values();
        text.append("Labels in ").append(window(window, definition.timezone())).append(" (").append(connection).append("): ").append(names.size()).append('.');
        var listed = names.stream().sorted().limit(LABELS).toList();
        for (String name : listed) {
            try {
                labels.put(name, new TreeSet<>(client.labelValues(connection, name, window.start(), window.end(), null).values()));
            } catch (LokiOperationException error) {
                if (error.error().code() == ErrorCode.OPERATION_CANCELLED) throw error;
                labels.put(name, Set.of());
            }
        }
        if (names.size() > listed.size())
            text.append(" Showing the first ").append(listed.size()).append(" label names.");
        appendLabels(text, labels);
        // Sample from the most service-like label so that the example reflects application logs.
        String scopeLabel = definition.serviceLabels().stream().filter(labels::containsKey).findFirst()
                .orElse(listed.isEmpty() ? null : listed.getFirst());
        return scopeLabel == null ? null : "{" + scopeLabel + "=~\".+\"}";
    }

    private Sample sample(String connection, String scope, QueryTime.Range window, ConnectionDefinition definition) {
        if (scope == null) return new Sample(List.of(), 0, 0, Map.of(), Set.of());
        int limit = Math.min(SAMPLE_ENTRIES, definition.limits().maxEntries());
        LokiResponses.QueryResponse response;
        try {
            response = client.queryRange(connection, scope, window.start(), window.end(), limit, LokiHttpClient.Direction.BACKWARD, null);
        } catch (LokiOperationException error) {
            if (error.error().code() == ErrorCode.OPERATION_CANCELLED) throw error;
            return new Sample(List.of(), 0, 0, Map.of(), Set.of());
        }
        if (!(response.data() instanceof LokiResponses.Streams streams))
            return new Sample(List.of(), 0, 0, Map.of(), Set.of());
        var events = new ArrayList<LogEvent>();
        for (var stream : streams.streams())
            for (var entry : stream.entries())
                events.add(new LogEvent(entry.timestampNanos(), stream.labels(), entry.line(), entry.structuredMetadata()));
        events.sort(Comparator.comparingLong(LogEvent::nanos).reversed());
        if (events.size() > limit) events.subList(limit, events.size()).clear();
        var fields = new LinkedHashMap<String, Integer>();
        var levels = new TreeSet<String>();
        int json = 0, plain = 0;
        for (var event : events) {
            var view = normalizer(definition).view(event, definition.serviceLabels());
            if (view.format() == EventNormalizer.Format.JSON) json++;
            else plain++;
            for (String path : view.jsonFields().keySet())
                fields.merge(EventNormalizer.lokiFieldName(path), 1, Integer::sum);
            if (view.level() != null) levels.add(view.level());
        }
        return new Sample(events, json, plain, fields, levels);
    }

    private record Sample(List<LogEvent> events, int json, int plain, Map<String, Integer> fields, Set<String> levels) {
    }
}
