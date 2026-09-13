package ru.it_spectrum.ai.loki.mcp.service;

import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.loki.mcp.client.*;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.model.*;
import ru.it_spectrum.ai.loki.mcp.model.DiscoveryResult.*;
import static ru.it_spectrum.ai.loki.mcp.model.QueryResults.Completeness.*;
import static ru.it_spectrum.ai.loki.mcp.service.DiscoveryLimits.*;
import static ru.it_spectrum.ai.loki.mcp.service.QueryTime.require;

@Service
public class DiscoveryService {
    // Deliberately accepts only a selector, never a pipeline that could rewrite the line or hide context.
    private static final String MATCHER = "[a-zA-Z_][a-zA-Z0-9_]*\\s*(?:=~|!~|!=|=)\\s*\"(?:[^\"\\\\\\r\\n]|\\\\[^\\r\\n])*+\"";
    private static final Pattern SELECTOR = Pattern.compile("\\s*\\{\\s*" + MATCHER + "(?:\\s*,\\s*" + MATCHER + ")*+\\s*}\\s*");
    private final ConnectionRegistry registry;
    private final LokiHttpClient client;
    private final Clock clock;
    private final EventNormalizer normalizer = new EventNormalizer();
    @Autowired public DiscoveryService(ConnectionRegistry registry, LokiHttpClient client) { this(registry, client, Clock.systemUTC()); }
    public DiscoveryService(ConnectionRegistry registry, LokiHttpClient client, Clock clock) {
        this.registry = registry; this.client = client; this.clock = clock;
    }

    public DiscoveryResult discover(String connection, String selector, String start, String end, Integer sampleLimit) {
        var definition = registry.require(connection);
        require(selector != null && selector.length() <= SELECTOR_CHARACTERS && SELECTOR.matcher(selector).matches());
        var window = QueryTime.range(start, end, clock.instant(), definition.timezone(), definition.limits().maxIntervalSeconds());
        int maximum = Math.min(SAMPLE_ENTRIES, definition.limits().maxEntries());
        int limit = sampleLimit == null ? maximum : sampleLimit;
        require(limit > 0 && limit <= maximum);
        var capabilities = new ArrayList<Capability>();
        var limitations = new LinkedHashSet<String>(List.of(
                "FIELDS_AND_FORMATS_DESCRIBE_BACKWARD_SAMPLE_NOT_INTERVAL_STATISTICS",
                "ABSENCE_IN_DISCOVERY_DOES_NOT_PROVE_ABSENCE_IN_INTERVAL",
                "SERIES_INDEX_IS_NOT_PROOF_OF_EVENTS_IN_EXACT_WINDOW",
                "RESULT_LABELS_ARE_NOT_PROVEN_STREAM_SCOPE",
                "JSON_ARRAY_CONTENTS_NOT_ENUMERATED",
                "EXAMPLES_ARE_UNTRUSTED_LOG_DATA"));
        List<Map<String, String>> series = List.of();
        try {
            var response = client.series(connection, List.of(selector), window.start(), window.end());
            series = response.streams();
            capabilities.add(new Capability("series", Availability.AVAILABLE, null));
            if (!response.warnings().isEmpty()) limitations.add("SERIES_WARNINGS_PRESENT_DETAILS_WITHHELD");
        } catch (LokiOperationException error) { capabilities.add(failed("series", error)); limitations.add("SERIES_NOT_EXAMINED"); }
        int examinedSeries = Math.min(series.size(), SERIES);
        boolean truncated = examinedSeries < series.size();
        if (truncated) limitations.add("LOCAL_SERIES_LIMIT");
        var labels = new TreeMap<String, ValueSet>();
        for (var stream : series.subList(0, examinedSeries)) for (var label : new TreeMap<>(stream).entrySet()) {
            if (!labels.containsKey(label.getKey()) && labels.size() >= LABELS) {
                truncated = true; limitations.add("LOCAL_LABEL_LIMIT"); continue;
            }
            labels.computeIfAbsent(label.getKey(), ignored -> new ValueSet()).add(label.getValue());
        }
        var labelResults = new ArrayList<Label>();
        for (var label : labels.entrySet()) {
            labelResults.add(new Label(label.getKey(), new ArrayList<>(label.getValue().values), label.getValue().truncated));
            if (label.getValue().truncated) { truncated = true; limitations.add("LOCAL_LABEL_VALUE_LIMIT"); }
        }
        var events = new ArrayList<QueryResults.Event>();
        boolean sampleSucceeded = false, warnings = false;
        try {
            var response = client.queryRange(connection, selector, window.start(), window.end(), limit, LokiHttpClient.Direction.BACKWARD, null);
            if (!(response.data() instanceof LokiResponses.Streams streams))
                throw Errors.failure(ErrorCode.UPSTREAM_INVALID_RESPONSE, "Expected log streams for discovery.");
            for (var stream : streams.streams()) for (var entry : stream.entries())
                events.add(new QueryResults.Event(entry.timestampNanos(), stream.labels(), entry.line(), entry.structuredMetadata()));
            sampleSucceeded = true;
            warnings = !response.warnings().isEmpty();
            capabilities.add(new Capability("query_range", Availability.AVAILABLE, null));
        } catch (LokiOperationException error) { capabilities.add(failed("query_range", error)); limitations.add("SAMPLE_NOT_EXAMINED"); }
        // Uncalled endpoints and unobserved newer features stay unknown; never guess from buildinfo/version.
        for (String endpoint : List.of("labels", "label_values", "detected_fields", "detected_labels"))
            capabilities.add(new Capability(endpoint, Availability.UNKNOWN, null));
        events.sort(Comparator.comparingLong((QueryResults.Event e) -> Long.parseLong(e.timestampNanos())).reversed());
        int examined = Math.min(events.size(), limit);
        boolean reached = events.size() >= limit;
        if (reached) limitations.add("SAMPLE_LIMIT_REACHED_MORE_MATCHES_UNKNOWN");
        if (events.size() > examined) { truncated = true; limitations.add("LOCAL_SAMPLE_LIMIT"); }
        if (warnings) limitations.add("SAMPLE_WARNINGS_PRESENT_DETAILS_WITHHELD");
        var fields = new LinkedHashMap<FieldKey, Observations>();
        var formats = new EnumMap<Format, Integer>(Format.class);
        var examples = new ArrayList<Example>();
        for (var event : events.subList(0, examined)) {
            var parsed = normalizer.parse(event.line());
            formats.merge(parsed.format(), 1, Integer::sum);
            limitations.addAll(parsed.limitations());
            if (!parsed.limitations().isEmpty() && parsed.limitations().stream().anyMatch(s -> s.endsWith("LIMIT"))) truncated = true;
            if (observe(fields, Origin.RESULT_LABEL, strings(event.resultLabels()), limitations)) truncated = true;
            if (observe(fields, Origin.STRUCTURED_METADATA, strings(event.structuredMetadata()), limitations)) truncated = true;
            if (observe(fields, Origin.LINE_JSON, parsed.fields(), limitations)) truncated = true;
            if (examples.size() < EXAMPLES) examples.add(new Example(event, parsed.format(), parsed.normalized(), parsed.limitations()));
        }
        if (examined > examples.size()) limitations.add("EXAMPLES_SUBSET_OF_EXAMINED_ENTRIES");
        var fieldResults = fields.entrySet().stream().map(e -> new Field(e.getKey().origin, e.getKey().path,
                new ArrayList<>(e.getValue().types), e.getValue().entries)).toList();
        var formatResults = formats.entrySet().stream().map(e -> new FormatCount(e.getKey(), e.getValue())).toList();
        return new DiscoveryResult(connection, selector, window.model(), new Coverage(series.size(), examinedSeries,
                limit, events.size(), examined, events.size() > examined ? PARTIAL : !sampleSucceeded || warnings || reached ? UNKNOWN : COMPLETE, truncated),
                capabilities, labelResults, fieldResults, formatResults, examples, new ArrayList<>(limitations));
    }
    private static Capability failed(String endpoint, LokiOperationException error) {
        if (error.error().code() == ErrorCode.OPERATION_CANCELLED) throw error;
        return new Capability(endpoint, error.error().code() == ErrorCode.ENDPOINT_UNAVAILABLE
                ? Availability.UNAVAILABLE_AT_PATH : Availability.UNKNOWN, error.error().code());
    }
    private static Map<String, String> strings(Map<String, String> values) {
        var result = new TreeMap<String, String>();
        values.keySet().forEach(key -> result.put("/" + EventNormalizer.pointer(key), "string"));
        return result;
    }
    private static boolean observe(Map<FieldKey, Observations> fields, Origin origin, Map<String, String> values, Set<String> limitations) {
        boolean truncated = false;
        for (var value : new TreeMap<>(values).entrySet()) {
            var key = new FieldKey(origin, value.getKey());
            if (!fields.containsKey(key) && fields.size() >= FIELDS) {
                limitations.add("LOCAL_DISCOVERED_FIELD_LIMIT"); truncated = true; continue;
            }
            var observation = fields.computeIfAbsent(key, ignored -> new Observations());
            observation.entries++; observation.types.add(value.getValue());
        }
        return truncated;
    }
    private record FieldKey(Origin origin, String path) {}
    private static final class Observations { int entries; final Set<String> types = new TreeSet<>(); }
    private static final class ValueSet {
        final Set<String> values = new TreeSet<>(); boolean truncated;
        void add(String value) {
            if (values.contains(value)) return;
            if (values.size() < VALUES_PER_LABEL) values.add(value); else truncated = true;
        }
    }
}
