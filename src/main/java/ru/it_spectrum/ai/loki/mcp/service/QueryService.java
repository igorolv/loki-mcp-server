package ru.it_spectrum.ai.loki.mcp.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.client.LokiResponses;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;
import ru.it_spectrum.ai.loki.mcp.model.LogEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static ru.it_spectrum.ai.loki.mcp.service.LogText.*;

/**
 * Log pages, counts and metrics rendered as text. Each call is one bounded Loki request (two for time buckets).
 */
@Service
public class QueryService {
    public static final int DEFAULT_LIMIT = 50;
    public static final int DEFAULT_CONTEXT = 20;
    public static final int TIME_BUCKETS = 12;
    public static final int METRIC_STEPS = 20;
    private static final List<Duration> NICE_STEPS = List.of(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(10),
            Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(10),
            Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(2), Duration.ofHours(3),
            Duration.ofHours(6), Duration.ofHours(12), Duration.ofDays(1));
    private final ConnectionRegistry registry;
    private final LokiHttpClient client;
    private final Clock clock;
    private final EventNormalizer normalizer = new EventNormalizer();

    @Autowired
    public QueryService(ConnectionRegistry registry, LokiHttpClient client) {
        this(registry, client, Clock.systemUTC());
    }

    public QueryService(ConnectionRegistry registry, LokiHttpClient client, Clock clock) {
        this.registry = registry;
        this.client = client;
        this.clock = clock;
    }

    /**
     * Newest {@code limit} lines of the window, printed in chronological order.
     */
    public String logs(String connection, String query, String start, String end, Integer limit, Boolean raw) {
        var definition = registry.require(connection);
        requireLogQuery(query);
        var window = QueryTime.range(start, end, clock.instant(), definition.timezone(), definition.limits().maxIntervalSeconds());
        int usedLimit = limit == null ? Math.min(DEFAULT_LIMIT, definition.limits().maxEntries()) : limit;
        if (usedLimit <= 0 || usedLimit > definition.limits().maxEntries())
            throw Errors.invalid("limit must be between 1 and " + definition.limits().maxEntries() + " for this connection.");
        var events = fetch(connection, query, window, usedLimit, LokiHttpClient.Direction.BACKWARD);
        boolean more = events.size() >= usedLimit;
        ZoneId zone = definition.timezone();
        var lines = new ArrayList<String>(events.size());
        for (var event : events)
            lines.add(line(event, normalizer.view(event, definition.serviceLabels()), zone, Boolean.TRUE.equals(raw)));
        String header = query.strip() + " — " + connection + ", " + window(window, zone) + ", "
                + (events.isEmpty() ? "no matching lines." : more ? "newest " + events.size() + " of more:" : "all " + events.size() + " lines:");
        var marked = withDateMarkers(events, lines, zone);
        // Markers are not events; keep the two lists aligned when the oldest lines are dropped for the budget.
        return fit(header, marked, dropped -> footer(events, marked, dropped, more, usedLimit, zone),
                definition.limits().maxResponseBytes() - ENVELOPE_BYTES);
    }

    private static String footer(List<LogEvent> events, List<String> marked, int dropped, boolean more, int limit, ZoneId zone) {
        if (events.isEmpty()) return "No lines match in this window. Try a wider window (e.g. start=\"now-6h\"), "
                + "check labels and fields with discoverLogs, or simplify the filter.";
        int markers = (int) marked.subList(0, Math.min(dropped, marked.size())).stream().filter(l -> l.startsWith("--- ")).count();
        int shown = events.size() - (dropped - markers);
        if (shown < 1) shown = 1;
        var oldest = QueryTime.fromNanos(events.get(events.size() - shown).timestampNanos());
        var text = new StringBuilder();
        if (dropped > 0)
            text.append("Output limit reached: showing ").append(shown).append(" newest of ").append(events.size()).append(" fetched lines. ");
        if (more || dropped > 0) {
            text.append("Shown ").append(shown).append(" newest lines; oldest shown ").append(iso(oldest, zone)).append(". ");
            text.append("Older: repeat with end=\"").append(iso(QueryTime.ceilMillis(oldest), zone)).append("\". ");
            text.append("Too many lines? Narrow the query (add a filter or level) or use countLogs.");
        } else text.append("Shown all ").append(shown).append(" matching lines.");
        return text.toString();
    }

    /**
     * Lines of one stream selector around a moment: {@code before} lines up to it and {@code after} lines past it,
     * counted in lines rather than time, so the result does not depend on how busy the service is.
     * Lines at the moment itself (within the precision of {@code time}) are marked with {@code >>>}.
     */
    public String context(String connection, String selector, String time, Integer before, Integer after) {
        var definition = registry.require(connection);
        if (selector == null || selector.isBlank())
            throw Errors.invalid("selector is required: the stream selector of the line, e.g. {app=\"backend\"}.");
        if (selector.length() > DiscoveryLimits.SELECTOR_CHARACTERS || !DiscoveryService.SELECTOR.matcher(selector).matches())
            throw Errors.invalid("selector must be a stream selector only, like {app=\"backend\"}: no |= filters or | json, so that "
                    + "neighbouring lines without the filtered text (e.g. stack trace continuations) are shown. "
                    + "Take it from discoverLogs or from the braces of the query you used.");
        if (time == null || time.isBlank())
            throw Errors.invalid("time is required: the time of the line as printed by queryLogs, e.g. \"10:12:03.123\", or \"2026-09-13T10:12:03.123+03:00\".");
        int maximum = definition.limits().maxEntries();
        int wantBefore = before == null ? Math.min(DEFAULT_CONTEXT, maximum - 1) : before;
        int wantAfter = after == null ? Math.min(DEFAULT_CONTEXT, maximum) : after;
        if (wantBefore < 0 || wantAfter < 0 || wantBefore >= maximum || wantAfter > maximum)
            throw Errors.invalid("before and after must be between 0 and " + (maximum - 1) + " for this connection.");
        ZoneId zone = definition.timezone();
        var point = QueryTime.point(time, clock.instant(), zone);
        Duration reach = Duration.ofSeconds(definition.limits().maxIntervalSeconds());
        String scope = selector.strip();
        // One extra line backward for the target itself; the forward page starts right after the moment.
        var earlier = fetch(connection, scope, new QueryTime.Range(point.end().minus(reach), point.end()), wantBefore + 1, LokiHttpClient.Direction.BACKWARD);
        var later = wantAfter == 0 ? List.<LogEvent>of()
                : fetch(connection, scope, new QueryTime.Range(point.end(), point.end().plus(reach)), wantAfter, LokiHttpClient.Direction.FORWARD);
        int targets = (int) earlier.stream().filter(e -> !QueryTime.fromNanos(e.timestampNanos()).isBefore(point.at())).count();
        // Without a line at the moment the spare slot holds one more old line than asked for; drop it.
        if (targets == 0 && earlier.size() > wantBefore)
            earlier = new ArrayList<>(earlier.subList(earlier.size() - wantBefore, earlier.size()));
        int beforeCount = earlier.size() - targets;
        var events = new ArrayList<LogEvent>(earlier);
        events.addAll(later);
        var lines = new ArrayList<String>(events.size());
        for (int i = 0; i < events.size(); i++) {
            String text = line(events.get(i), normalizer.view(events.get(i), definition.serviceLabels()), zone, false);
            lines.add(i >= beforeCount && i < earlier.size() ? ">>> " + text : text);
        }
        String moment = DATE_TIME.format(point.at().atZone(zone)) + (point.precision().compareTo(Duration.ofSeconds(1)) < 0
                ? "." + String.format("%03d", point.at().atZone(zone).getNano() / 1_000_000) : "");
        String header = "Context in " + scope + " around " + moment + " (" + point.at().atZone(zone).getOffset() + ") — " + connection
                + ", lines: " + beforeCount + " before, " + targets + " at that time, " + later.size() + " after:";
        String placeholder = targets == 0 ? ">>> (no line at exactly this time in " + scope + "; lines before and after it follow)" : null;
        int budget = definition.limits().maxResponseBytes() - ENVELOPE_BYTES;
        // The moment stays in the middle: trim the longer side first, never the marked lines.
        int keepBefore = beforeCount, keepAfter = later.size();
        while (true) {
            var window = events.subList(beforeCount - keepBefore, earlier.size() + keepAfter);
            var marked = withDateMarkers(window, lines.subList(beforeCount - keepBefore, earlier.size() + keepAfter), zone);
            if (placeholder != null) marked.add(marked.size() - keepAfter, placeholder);
            int dropped = (beforeCount - keepBefore) + (later.size() - keepAfter);
            String text = assemble(header, marked, contextFooter(earlier, later, targets, wantBefore, wantAfter, keepBefore, keepAfter, dropped, point, reach, zone));
            if (bytes(text) <= budget) return text;
            if (keepBefore == 0 && keepAfter == 0) throw Errors.failure(ErrorCode.RESPONSE_BUDGET_EXCEEDED,
                    "Even a minimal response does not fit maxResponseBytes of this connection. Narrow the selector or raise the limit.");
            if (keepAfter > keepBefore) keepAfter--;
            else keepBefore--;
        }
    }

    private static String contextFooter(List<LogEvent> earlier, List<LogEvent> later, int targets, int wantBefore, int wantAfter,
                                        int keepBefore, int keepAfter, int dropped, QueryTime.Point point, Duration reach, ZoneId zone) {
        var text = new StringBuilder();
        int beforeCount = earlier.size() - targets;
        if (dropped > 0)
            text.append("Output limit reached: showing ").append(keepBefore).append(" before and ").append(keepAfter)
                    .append(" after of ").append(earlier.size() + later.size()).append(" fetched lines. ");
        String span = QueryTime.human(reach);
        if (targets > 0 && targets == earlier.size() && targets > wantBefore)
            text.append("All ").append(targets).append(" fetched lines are at this time; ")
                    .append(point.precision().compareTo(Duration.ofMillis(1)) > 0 ? "pass the time with milliseconds as printed by queryLogs"
                            : "repeat with a larger before (e.g. before=" + Math.min(wantBefore * 5 + 5, 200) + ")")
                    .append(" to see what came before. ");
        else if (beforeCount < wantBefore)
            text.append("No earlier lines within ").append(span).append(" before this time. ");
        else if (keepBefore > 0)
            text.append("Earlier: repeat with time=\"").append(iso(QueryTime.fromNanos(earlier.get(beforeCount - keepBefore).timestampNanos()), zone)).append("\", after=0. ");
        if (wantAfter > 0 && later.size() < wantAfter)
            text.append("No later lines within ").append(span).append(" after this time. ");
        else if (keepAfter > 0)
            text.append("Later: repeat with time=\"").append(iso(QueryTime.fromNanos(later.get(keepAfter - 1).timestampNanos()), zone)).append("\", before=0. ");
        text.append("Full original line: queryLogs with raw=true and a narrow filter.");
        return text.toString();
    }

    /**
     * Count of matching lines, optionally broken down by a label or by time buckets.
     */
    public String count(String connection, String query, String start, String end, String groupBy) {
        var definition = registry.require(connection);
        requireLogQuery(query);
        var window = QueryTime.range(start, end, clock.instant(), definition.timezone(), definition.limits().maxIntervalSeconds());
        ZoneId zone = definition.timezone();
        String where = query.strip() + " in " + window(window, zone) + " (" + connection + ")";
        if (groupBy == null || groupBy.isBlank()) {
            long total = sum(vector(client.queryInstant(connection, countExpression(query, window.duration(), null), window.end())));
            return total + " lines match " + where + ".";
        }
        if (groupBy.strip().equals("time")) return buckets(connection, query, window, zone);
        String label = groupBy.strip();
        if (!label.matches("[a-zA-Z_][a-zA-Z0-9_]*"))
            throw Errors.invalid("groupBy must be a label name (letters, digits, underscore) or \"time\".");
        var rows = new ArrayList<Map.Entry<String, Long>>();
        for (var sample : vector(client.queryInstant(connection, countExpression(query, window.duration(), label), window.end())))
            rows.add(Map.entry(sample.labels().getOrDefault(label, ""), value(sample.sample().value())));
        rows.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        long total = rows.stream().mapToLong(Map.Entry::getValue).sum();
        var text = new StringBuilder(total + " lines match " + where + ".");
        if (rows.isEmpty()) return text.toString();
        text.append("\nBy ").append(label).append(':');
        int width = Math.max(6, rows.stream().mapToInt(r -> r.getKey().length()).max().orElse(1));
        int shown = 0;
        for (var row : rows) {
            if (shown++ >= DEFAULT_LIMIT) {
                text.append("\n  (+").append(rows.size() - DEFAULT_LIMIT).append(" more values)");
                break;
            }
            text.append("\n  ").append(String.format("%-" + width + "s  %d", row.getKey().isEmpty() ? "(none)" : row.getKey(), row.getValue()));
        }
        return text.toString();
    }

    private String buckets(String connection, String query, QueryTime.Range window, ZoneId zone) {
        // Clock-aligned "nice" steps: Loki itself aligns metric evaluations to multiples of the step (split by interval),
        // so requesting other timestamps yields buckets nobody asked for. The edge buckets may extend past the window.
        Duration step = niceStep(window.duration(), TIME_BUCKETS);
        long seconds = step.toSeconds();
        Instant first = Instant.ofEpochSecond(Math.floorDiv(window.start().getEpochSecond(), seconds) * seconds + seconds);
        long endSecond = window.end().getEpochSecond() + (window.end().getNano() > 0 ? 1 : 0);
        Instant last = Instant.ofEpochSecond(Math.floorDiv(endSecond + seconds - 1, seconds) * seconds);
        if (!first.isBefore(last)) last = first.plus(step);
        // Each evaluation at t counts (t-step, t].
        var response = client.queryRange(connection, countExpression(query, step, null), first, last,
                registry.require(connection).limits().maxEntries(), LokiHttpClient.Direction.FORWARD, stepSeconds(step));
        if (!(response.data() instanceof LokiResponses.Matrix matrix)) throw notMetric();
        String where = query.strip() + " in " + window(new QueryTime.Range(first.minus(step), last), zone) + " (" + connection + ")";
        var counts = new TreeMap<Long, Long>();
        for (Instant t = first; !t.isAfter(last); t = t.plus(step)) counts.put(t.getEpochSecond(), 0L);
        for (var series : matrix.series())
            for (var sample : series.samples()) {
                long at = sample.timestampSeconds().setScale(0, RoundingMode.HALF_UP).longValueExact();
                counts.merge(at, value(sample.value()), Long::sum);
            }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        var text = new StringBuilder(total + " lines match " + where + ".");
        if (total == 0) return text.toString();
        var sorted = new ArrayList<>(counts.values());
        Collections.sort(sorted);
        double median = sorted.get(sorted.size() / 2);
        double reference = median > 0 ? median : (double) total / sorted.size();
        var format = step.toSeconds() < 60 ? DateTimeFormatter.ofPattern("HH:mm:ss") : DateTimeFormatter.ofPattern("HH:mm");
        text.append("\nBy time (").append(QueryTime.lokiDuration(step)).append(" buckets, bucket start):");
        for (var bucket : counts.entrySet()) {
            Instant bucketStart = Instant.ofEpochSecond(bucket.getKey()).minus(step);
            text.append("\n  ").append(format.format(bucketStart.atZone(zone))).append(String.format("  %6d", bucket.getValue()));
            if (bucket.getValue() >= 5 && bucket.getValue() >= 3 * reference) text.append("  <- spike");
        }
        return text.toString();
    }

    /**
     * Range metric query as a table: one block per series, one row per point.
     */
    public String metrics(String connection, String query, String start, String end, String step) {
        var definition = registry.require(connection);
        if (query == null || query.isBlank())
            throw Errors.invalid("query is required: a metric LogQL expression such as sum(rate({app=\"x\"}[5m])).");
        if (query.strip().startsWith("{"))
            throw Errors.invalid("This looks like a log query. Use queryLogs to read lines or countLogs to count them; "
                    + "queryMetrics needs a metric expression such as sum by (level) (count_over_time({app=\"x\"}[5m])).");
        var window = QueryTime.range(start, end, clock.instant(), definition.timezone(), definition.limits().maxIntervalSeconds());
        Duration used = step == null || step.isBlank() ? niceStep(window.duration()) : QueryTime.duration(step);
        long evaluations = window.duration().toMillis() / used.toMillis() + 1;
        if (evaluations > definition.limits().maxMetricPoints())
            throw Errors.invalid("step \"" + QueryTime.lokiDuration(used) + "\" gives " + evaluations + " points per series; use a larger step or a shorter window.");
        var response = client.queryRange(connection, query, window.start(), window.end(), definition.limits().maxEntries(),
                LokiHttpClient.Direction.FORWARD, stepSeconds(used));
        if (!(response.data() instanceof LokiResponses.Matrix matrix)) throw notMetric();
        ZoneId zone = definition.timezone();
        var text = new StringBuilder(query.strip() + " — " + connection + ", " + window(window, zone) + ", step " + QueryTime.lokiDuration(used));
        if (matrix.series().isEmpty())
            return text.append(": no series. The expression matched no data in this window.").toString();
        int maxSeries = definition.limits().maxMetricSeries(), maxPoints = definition.limits().maxMetricPoints(), points = 0, shownSeries = 0;
        var format = used.toSeconds() < 60 ? DateTimeFormatter.ofPattern("HH:mm:ss") : DateTimeFormatter.ofPattern("MM-dd HH:mm");
        text.append(", ").append(matrix.series().size()).append(matrix.series().size() == 1 ? " series:" : " series:");
        boolean trimmed = false;
        for (var series : matrix.series()) {
            if (shownSeries >= maxSeries || points >= maxPoints) {
                trimmed = true;
                break;
            }
            text.append('\n').append(labels(series.labels()));
            for (var sample : series.samples()) {
                if (points >= maxPoints) {
                    trimmed = true;
                    break;
                }
                text.append("\n  ").append(format.format(Instant.ofEpochSecond(sample.timestampSeconds().setScale(0, RoundingMode.DOWN).longValueExact()).atZone(zone)))
                        .append("  ").append(sample.value());
                points++;
            }
            shownSeries++;
        }
        if (trimmed) text.append("\nOutput trimmed to ").append(shownSeries).append(" series / ").append(points)
                .append(" points (connection limits). Aggregate with sum by (...) or use a larger step.");
        return fit(text.toString(), List.of(), ignored -> "", definition.limits().maxResponseBytes() - ENVELOPE_BYTES);
    }

    /**
     * Chronological page of at most {@code limit} entries: the newest ones for BACKWARD, the oldest ones for FORWARD.
     */
    List<LogEvent> fetch(String connection, String query, QueryTime.Range window, int limit, LokiHttpClient.Direction direction) {
        var response = client.queryRange(connection, query, window.start(), window.end(), limit, direction, null);
        if (!(response.data() instanceof LokiResponses.Streams streams))
            throw Errors.invalid("This is a metric expression; queryLogs reads log lines. Use queryMetrics for metric LogQL.");
        var events = new ArrayList<LogEvent>();
        for (var stream : streams.streams())
            for (var entry : stream.entries())
                events.add(new LogEvent(entry.timestampNanos(), stream.labels(), entry.line(), entry.structuredMetadata()));
        events.sort(Comparator.comparingLong(LogEvent::nanos)); // Stable: identical timestamps keep upstream order and multiplicity.
        // A page larger than the limit is trimmed on the side the direction did not favour.
        if (events.size() <= limit) return events;
        return new ArrayList<>(direction == LokiHttpClient.Direction.BACKWARD ? events.subList(events.size() - limit, events.size()) : events.subList(0, limit));
    }

    static String countExpression(String query, Duration range, String by) {
        String inner = "count_over_time(" + query.strip() + " [" + QueryTime.lokiDuration(range) + "])";
        return by == null ? "sum(" + inner + ")" : "sum by (" + by + ") (" + inner + ")";
    }

    static Duration niceStep(Duration window) {
        return niceStep(window, METRIC_STEPS);
    }

    static Duration niceStep(Duration window, int points) {
        Duration minimum = window.dividedBy(points);
        for (var candidate : NICE_STEPS) if (candidate.compareTo(minimum) >= 0) return candidate;
        return NICE_STEPS.getLast();
    }

    private static void requireLogQuery(String query) {
        if (query == null || query.isBlank())
            throw Errors.invalid("query is required, e.g. {app=\"backend\"} |= \"ERROR\".");
        if (!query.strip().startsWith("{"))
            throw Errors.invalid("A log query starts with a stream selector in braces, e.g. {app=\"backend\"} |= \"ERROR\". "
                    + "For metric expressions use queryMetrics.");
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

    private static BigDecimal stepSeconds(Duration step) {
        return BigDecimal.valueOf(step.toMillis(), 3);
    }

    private static LokiOperationException notMetric() {
        return Errors.invalid("Loki returned a result type this tool cannot show. Check that the expression is what the tool expects.");
    }
}
