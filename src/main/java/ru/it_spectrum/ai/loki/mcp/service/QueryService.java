package ru.it_spectrum.ai.loki.mcp.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.client.LokiResponses;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.model.LogEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static ru.it_spectrum.ai.loki.mcp.service.LogText.ENVELOPE_BYTES;
import static ru.it_spectrum.ai.loki.mcp.service.LogText.fit;
import static ru.it_spectrum.ai.loki.mcp.service.LogText.iso;
import static ru.it_spectrum.ai.loki.mcp.service.LogText.line;
import static ru.it_spectrum.ai.loki.mcp.service.LogText.window;
import static ru.it_spectrum.ai.loki.mcp.service.LogText.withDateMarkers;

/**
 * Bounded log pages and Loki-side counts rendered as text.
 */
@Service
public class QueryService {
    public static final int DEFAULT_LIMIT = 50;
    public static final int TIME_BUCKETS = 12;
    private static final List<Duration> NICE_STEPS = List.of(Duration.ofSeconds(1), Duration.ofSeconds(5),
            Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2),
            Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(15), Duration.ofMinutes(30),
            Duration.ofHours(1), Duration.ofHours(2), Duration.ofHours(3), Duration.ofHours(6),
            Duration.ofHours(12), Duration.ofDays(1));
    private final ConnectionRegistry registry;
    private final LokiHttpClient client;
    private final Clock clock;

    @Autowired
    public QueryService(ConnectionRegistry registry, LokiHttpClient client) {
        this(registry, client, Clock.systemUTC());
    }

    public QueryService(ConnectionRegistry registry, LokiHttpClient client, Clock clock) {
        this.registry = registry;
        this.client = client;
        this.clock = clock;
    }

    static void requireLogQuery(String query) {
        if (query == null || query.isBlank()) {
            throw Errors.invalid("query is required: pass a LogQL log query such as {app=\"backend\"} |= \"ERROR\".");
        }
        if (!query.strip().startsWith("{")) {
            throw Errors.invalid("A log query starts with a stream selector, e.g. {app=\"backend\"} |= \"ERROR\". "
                    + "Use countLogs to count lines.");
        }
    }

    public String logs(String connection, String query, String start, String end, Integer limit, Boolean raw) {
        var definition = registry.require(connection);
        requireLogQuery(query);
        var range = QueryTime.range(start, end, clock.instant(), definition.timezone(), definition.limits().maxIntervalSeconds());
        int usedLimit = limit == null ? Math.min(DEFAULT_LIMIT, definition.limits().maxEntries()) : limit;
        if (usedLimit <= 0 || usedLimit > definition.limits().maxEntries()) {
            throw Errors.invalid("limit must be between 1 and " + definition.limits().maxEntries() + " for this connection.");
        }
        var events = fetch(connection, query, range, usedLimit, LokiHttpClient.Direction.BACKWARD);
        boolean atLimit = events.size() == usedLimit;
        ZoneId zone = definition.timezone();
        var normalizer = new EventNormalizer(definition.formats());
        var lines = new ArrayList<String>(events.size());
        for (var event : events) {
            lines.add(line(event, normalizer.view(event, definition.serviceLabels()), zone, Boolean.TRUE.equals(raw)));
        }
        String header = query.strip() + " — " + connection + ", " + window(range, zone) + ", "
                + (events.isEmpty() ? "no matching lines." : atLimit ? "newest " + events.size() + " lines (more may exist):"
                : "all " + events.size() + " lines:");
        var marked = withDateMarkers(events, lines, zone);
        return fit(header, marked, dropped -> pageFooter(events, marked, dropped, atLimit, zone),
                definition.limits().maxResponseBytes() - ENVELOPE_BYTES);
    }

    private static String pageFooter(List<LogEvent> events, List<String> marked, int dropped, boolean atLimit, ZoneId zone) {
        if (events.isEmpty()) {
            return "Try a wider window, inspect labels with discoverLogs, or simplify the LogQL filter.";
        }
        int markers = (int) marked.subList(0, Math.min(dropped, marked.size())).stream()
                .filter(value -> value.startsWith("--- ")).count();
        int shown = Math.max(1, events.size() - (dropped - markers));
        if (!atLimit && dropped == 0) return "Shown all " + shown + " matching lines.";
        var oldest = QueryTime.fromNanos(events.get(events.size() - shown).timestampNanos());
        var footer = new StringBuilder();
        if (dropped > 0) {
            footer.append("Output limit reached: showing ").append(shown).append(" newest of ")
                    .append(events.size()).append(" fetched lines. ");
        }
        footer.append("Oldest shown ").append(iso(oldest, zone)).append(". Older: repeat with end=\"")
                .append(iso(QueryTime.ceilMillis(oldest), zone)).append("\"; boundary lines may repeat. ")
                .append("If the same timestamp fills every page, narrow the query. ")
                .append("For many lines, use countLogs or exportLogs.");
        return footer.toString();
    }

    public String count(String connection, String query, String start, String end, String groupBy) {
        var definition = registry.require(connection);
        requireLogQuery(query);
        var range = QueryTime.range(start, end, clock.instant(), definition.timezone(), definition.limits().maxIntervalSeconds());
        ZoneId zone = definition.timezone();
        String where = query.strip() + " in " + window(range, zone) + " (" + connection + ")";
        if (groupBy == null || groupBy.isBlank()) {
            long total = sum(vector(client.queryInstant(connection, countExpression(query, range.duration(), null), range.end())));
            return total + " lines match " + where + ".";
        }
        if (groupBy.strip().equals("time")) return buckets(connection, query, range, zone);
        String label = groupBy.strip();
        if (!label.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw Errors.invalid("groupBy must be a label name or \"time\".");
        }
        var rows = new ArrayList<Map.Entry<String, Long>>();
        for (var sample : vector(client.queryInstant(connection, countExpression(query, range.duration(), label), range.end()))) {
            rows.add(Map.entry(sample.labels().getOrDefault(label, ""), value(sample.sample().value())));
        }
        rows.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        long total = rows.stream().mapToLong(Map.Entry::getValue).sum();
        var text = new StringBuilder(total + " lines match " + where + ".");
        if (rows.isEmpty()) return text.toString();
        text.append("\nBy ").append(label).append(':');
        int width = Math.max(6, rows.stream().mapToInt(row -> row.getKey().length()).max().orElse(1));
        for (int i = 0; i < Math.min(DEFAULT_LIMIT, rows.size()); i++) {
            var row = rows.get(i);
            text.append("\n  ").append(String.format("%-" + width + "s  %d",
                    row.getKey().isEmpty() ? "(none)" : row.getKey(), row.getValue()));
        }
        if (rows.size() > DEFAULT_LIMIT) text.append("\n  (+").append(rows.size() - DEFAULT_LIMIT).append(" more values)");
        return text.toString();
    }

    static String countExpression(String query, Duration range, String by) {
        String inner = "count_over_time(" + query.strip() + " [" + QueryTime.lokiDuration(range) + "])";
        return by == null ? "sum(" + inner + ")" : "sum by (" + by + ") (" + inner + ")";
    }

    static Duration niceStep(Duration window, int points) {
        Duration minimum = window.dividedBy(points);
        for (var candidate : NICE_STEPS) {
            if (candidate.compareTo(minimum) >= 0) return candidate;
        }
        return NICE_STEPS.getLast();
    }

    private String buckets(String connection, String query, QueryTime.Range range, ZoneId zone) {
        Duration step = niceStep(range.duration(), TIME_BUCKETS);
        long seconds = step.toSeconds();
        Instant first = Instant.ofEpochSecond(Math.floorDiv(range.start().getEpochSecond(), seconds) * seconds + seconds);
        long endSecond = range.end().getEpochSecond() + (range.end().getNano() > 0 ? 1 : 0);
        Instant last = Instant.ofEpochSecond(Math.floorDiv(endSecond + seconds - 1, seconds) * seconds);
        if (!first.isBefore(last)) last = first.plus(step);
        var response = client.queryRange(connection, countExpression(query, step, null), first, last,
                registry.require(connection).limits().maxEntries(), LokiHttpClient.Direction.FORWARD,
                BigDecimal.valueOf(step.toMillis(), 3));
        if (!(response.data() instanceof LokiResponses.Matrix matrix)) throw notMetric();
        var counts = new TreeMap<Long, Long>();
        for (Instant t = first; !t.isAfter(last); t = t.plus(step)) counts.put(t.getEpochSecond(), 0L);
        for (var series : matrix.series()) {
            for (var sample : series.samples()) {
                long at = sample.timestampSeconds().setScale(0, RoundingMode.HALF_UP).longValueExact();
                counts.merge(at, value(sample.value()), Long::sum);
            }
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        String where = query.strip() + " in " + window(new QueryTime.Range(first.minus(step), last), zone)
                + " (" + connection + ")";
        var text = new StringBuilder(total + " lines match " + where + ".");
        var format = step.toSeconds() < 60 ? DateTimeFormatter.ofPattern("HH:mm:ss")
                : DateTimeFormatter.ofPattern("HH:mm");
        text.append("\nBy time (").append(QueryTime.lokiDuration(step)).append(" buckets, bucket start):");
        for (var bucket : counts.entrySet()) {
            Instant bucketStart = Instant.ofEpochSecond(bucket.getKey()).minus(step);
            text.append("\n  ").append(format.format(bucketStart.atZone(zone)))
                    .append(String.format("  %6d", bucket.getValue()));
        }
        return text.toString();
    }

    private static List<LokiResponses.VectorSample> vector(LokiResponses.QueryResponse response) {
        if (!(response.data() instanceof LokiResponses.Vector vector)) throw notMetric();
        return vector.samples();
    }

    private static long sum(List<LokiResponses.VectorSample> samples) {
        long total = 0;
        for (var sample : samples) total += value(sample.sample().value());
        return total;
    }

    private static long value(String metricValue) {
        try {
            return new BigDecimal(metricValue).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (NumberFormatException | ArithmeticException ignored) {
            return 0;
        }
    }

    private static LokiOperationException notMetric() {
        return Errors.invalid("Loki returned a result type this tool cannot show. Check the query.");
    }

    List<LogEvent> fetch(String connection, String query, QueryTime.Range range, int limit, LokiHttpClient.Direction direction) {
        var response = client.queryRange(connection, query, range.start(), range.end(), limit, direction, null);
        if (!(response.data() instanceof LokiResponses.Streams streams)) {
            throw Errors.invalid("This is a metric expression; queryLogs reads log lines. Use countLogs to count them.");
        }
        var events = new ArrayList<LogEvent>();
        for (var stream : streams.streams()) {
            for (var entry : stream.entries()) {
                events.add(new LogEvent(entry.timestampNanos(), stream.labels(), entry.line(), entry.structuredMetadata()));
            }
        }
        events.sort(Comparator.comparingLong(LogEvent::nanos));
        if (events.size() <= limit) return events;
        return new ArrayList<>(direction == LokiHttpClient.Direction.BACKWARD
                ? events.subList(events.size() - limit, events.size()) : events.subList(0, limit));
    }
}
