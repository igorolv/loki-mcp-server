package ru.it_spectrum.ai.loki.mcp.service;

import java.util.*;
import ru.it_spectrum.ai.loki.mcp.model.CompactLogs;
import ru.it_spectrum.ai.loki.mcp.model.DiscoveryResult.NormalizedValue;
import ru.it_spectrum.ai.loki.mcp.model.QueryResults;

/** Projection is local: it does not rewrite LogQL or claim to recover its input. */
public final class ResponseProjection {
    public static final int TEXT_CODE_POINTS = 2048;
    public static final int MIN_TEXT_CODE_POINTS = 128;
    public static final List<String> DEFAULT_FIELDS = List.of("line");
    private static final Set<String> ALLOWED_FIELDS = Set.of("line", "structuredMetadata", "normalized");
    private ResponseProjection() {}

    public static List<String> fields(List<String> requested) {
        if (requested == null) return DEFAULT_FIELDS;
        QueryTime.require(requested.size() <= ALLOWED_FIELDS.size()
                && requested.stream().allMatch(f -> f != null && ALLOWED_FIELDS.contains(f))
                && new HashSet<>(requested).size() == requested.size());
        return List.copyOf(requested);
    }

    public static CompactLogs project(QueryResults.Logs logs, List<String> fields) {
        var dictionary = new LinkedHashMap<Map<String, String>, String>();
        var events = new ArrayList<CompactLogs.Event>();
        var normalizer = new EventNormalizer();
        for (var event : logs.events()) {
            String id = dictionary.computeIfAbsent(event.resultLabels(), ignored -> "s" + dictionary.size());
            var truncated = new ArrayList<String>();
            var limitations = new ArrayList<String>();
            String line = fields.contains("line") ? shorten(event.line(), "line", truncated) : null;
            List<NormalizedValue> normalized = null;
            if (fields.contains("normalized")) {
                var parsed = normalizer.parse(event.line());
                limitations.addAll(parsed.limitations());
                normalized = parsed.normalized().stream().map(value -> new NormalizedValue(value.name(), value.origin(),
                        value.path(), shorten(value.value(), "normalized:" + value.path(), truncated))).toList();
            }
            events.add(new CompactLogs.Event(event.timestampNanos(), id, line,
                    fields.contains("structuredMetadata") ? event.structuredMetadata() : null,
                    normalized, truncated, limitations));
        }
        var limitations = new ArrayList<>(logs.limitations());
        limitations.add("PROJECTION_APPLIED_ORIGINAL_NOT_CACHED_DETAILS_UNAVAILABLE");
        if (events.stream().anyMatch(e -> !e.truncatedFields().isEmpty())) limitations.add("EVENT_FIELDS_TRUNCATED");
        return new CompactLogs(logs.connection(), logs.window(), logs.direction(), logs.limit(), logs.readEntries(),
                events.size(), logs.resultStreams(), logs.upstreamLimitReached(), logs.completeness(),
                logs.continuationUnavailableReason(), logs.totalLinesProcessed(), fields, limitations,
                dictionary.entrySet().stream().map(e -> new CompactLogs.Stream(e.getValue(), e.getKey())).toList(), events);
    }

    private static String shorten(String text, String path, List<String> truncated) {
        int length = text.codePointCount(0, text.length());
        if (length <= TEXT_CODE_POINTS) return text;
        truncated.add(path);
        return abbreviate(text, TEXT_CODE_POINTS);
    }

    public static String abbreviate(String text, int codePoints) {
        int length = text.codePointCount(0, text.length());
        if (length <= codePoints) return text;
        // Keep the exception/message prefix and tail frames; never split a surrogate pair.
        int head = codePoints * 3 / 4;
        return text.substring(0, text.offsetByCodePoints(0, head)) + "\n…[truncated]…\n"
                + text.substring(text.offsetByCodePoints(0, length - (codePoints - head)));
    }
}
