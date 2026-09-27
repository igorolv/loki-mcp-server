package ru.it_spectrum.ai.loki.mcp.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.client.LokiResponses;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionDefinition;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;
import ru.it_spectrum.ai.loki.mcp.model.LogEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static ru.it_spectrum.ai.loki.mcp.service.LogText.ENVELOPE_BYTES;
import static ru.it_spectrum.ai.loki.mcp.service.LogText.assemble;
import static ru.it_spectrum.ai.loki.mcp.service.LogText.bytes;
import static ru.it_spectrum.ai.loki.mcp.service.LogText.iso;
import static ru.it_spectrum.ai.loki.mcp.service.LogText.line;
import static ru.it_spectrum.ai.loki.mcp.service.LogText.window;

/**
 * Bounded log pages and Loki-side counts rendered as text.
 */
@Service
public class QueryService {
    public static final int DEFAULT_LIMIT = 50;
    public static final int TIME_BUCKETS = 12;
    private static final int MAX_TIME_BUCKETS = 200;
    private static final int MAX_BUCKET_ROWS = 200;
    private static final Pattern STEP = Pattern.compile("([1-9][0-9]*)(s|m|h|d)");
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
        return logs(connection, query, start, end, limit, raw, null);
    }

    public String logs(String connection, String query, String start, String end, Integer limit, Boolean raw,
                       String order) {
        var definition = registry.require(connection);
        requireLogQuery(query);
        String requestedOrder = order == null || order.isBlank() ? "newest" : order.strip();
        if (!requestedOrder.equals("newest") && !requestedOrder.equals("oldest")) {
            throw Errors.invalid("order must be \"newest\" or \"oldest\".");
        }
        boolean oldestFirst = requestedOrder.equals("oldest");
        var range = QueryTime.range(start, end, clock.instant(), definition.timezone(),
                definition.limits().maxIntervalSeconds());
        int usedLimit = limit == null ? Math.min(DEFAULT_LIMIT, definition.limits().maxEntries()) : limit;
        if (usedLimit <= 0 || usedLimit > definition.limits().maxEntries()) {
            throw Errors.invalid("limit must be between 1 and " + definition.limits().maxEntries() + " for this connection.");
        }
        var events = fetch(connection, query, range, usedLimit,
                oldestFirst ? LokiHttpClient.Direction.FORWARD : LokiHttpClient.Direction.BACKWARD);
        boolean atLimit = events.size() == usedLimit;
        ZoneId zone = definition.timezone();
        var rows = pageRows(events, definition, Boolean.TRUE.equals(raw));
        String header = query.strip() + " — " + connection + ", " + window(range, zone) + ", "
                + (events.isEmpty() ? "no matching lines." : atLimit
                ? requestedOrder + " " + events.size() + " lines (more may exist):"
                : "all " + events.size() + " lines:");
        return renderPage(header, rows, events.size(), atLimit, oldestFirst, zone,
                definition.limits().maxResponseBytes() - ENVELOPE_BYTES);
    }

    private static List<PageRow> pageRows(List<LogEvent> events, ConnectionDefinition definition, boolean raw) {
        var rows = new ArrayList<PageRow>();
        var normalizer = new EventNormalizer(definition.formats());
        LocalDate previousDay = null;
        for (int i = 0; i < events.size();) {
            LogEvent event = events.get(i);
            Instant first = QueryTime.fromNanos(event.timestampNanos());
            LocalDate day = first.atZone(definition.timezone()).toLocalDate();
            if (previousDay != null && !day.equals(previousDay)) {
                rows.add(new PageRow("--- " + day + " ---", null, null, 0));
            }
            previousDay = day;
            int end = i + 1;
            Pattern frame = definition.framePattern();
            if (!raw && frame != null && standaloneFrame(event.line(), frame)) {
                while (end < events.size()) {
                    LogEvent next = events.get(end);
                    Instant nextTime = QueryTime.fromNanos(next.timestampNanos());
                    if (!next.labels().equals(event.labels())
                            || !nextTime.atZone(definition.timezone()).toLocalDate().equals(day)
                            || !standaloneFrame(next.line(), frame)) break;
                    end++;
                }
            }
            String rendered;
            if (end - i > 1) {
                var view = normalizer.view(event, definition.serviceLabels());
                var summary = new EventNormalizer.View(EventNormalizer.Format.PLAIN, null, view.service(), null,
                        "… " + (end - i) + " stack frame lines", null, null);
                rendered = line(event, summary, definition.timezone(), false);
            } else {
                rendered = line(event, normalizer.view(event, definition.serviceLabels()), definition.timezone(), raw);
            }
            Instant last = QueryTime.fromNanos(events.get(end - 1).timestampNanos());
            rows.add(new PageRow(rendered, first, last, end - i));
            i = end;
        }
        return rows;
    }

    private static boolean standaloneFrame(String line, Pattern frame) {
        return line.indexOf('\n') < 0 && line.indexOf('\r') < 0 && frame.matcher(line).matches();
    }

    private static String renderPage(String header, List<PageRow> rows, int fetched, boolean atLimit,
                                     boolean oldestFirst, ZoneId zone, int budget) {
        var shown = new ArrayList<>(rows);
        while (true) {
            int count = shown.stream().mapToInt(PageRow::lines).sum();
            String footer = pageFooter(shown, fetched, count, atLimit, oldestFirst, zone);
            String result = assemble(header, shown.stream().map(PageRow::text).toList(), footer);
            if (bytes(result) <= budget && (count > 0 || fetched == 0)) return result;
            if (shown.isEmpty()) {
                throw Errors.failure(ErrorCode.RESPONSE_BUDGET_EXCEEDED,
                        "Even a minimal response does not fit maxResponseBytes of this connection. "
                                + "Narrow the query or raise the limit.");
            }
            if (oldestFirst) shown.removeLast();
            else shown.removeFirst();
            while (!shown.isEmpty() && shown.get(oldestFirst ? shown.size() - 1 : 0).lines() == 0) {
                if (oldestFirst) shown.removeLast();
                else shown.removeFirst();
            }
        }
    }

    private static String pageFooter(List<PageRow> shownRows, int fetched, int shown, boolean atLimit,
                                     boolean oldestFirst, ZoneId zone) {
        if (fetched == 0) {
            return "Try a wider window, inspect labels with discoverLogs, or simplify the LogQL filter.";
        }
        if (shown == 0) return "";
        if (!atLimit && shown == fetched) return "Shown all " + shown + " matching lines.";
        var footer = new StringBuilder();
        if (shown < fetched) {
            footer.append("Output limit reached: showing ").append(shown)
                    .append(oldestFirst ? " oldest of " : " newest of ")
                    .append(fetched).append(" fetched lines. ");
        }
        if (oldestFirst) {
            Instant newest = shownRows.stream().filter(row -> row.lines() > 0).reduce((left, right) -> right)
                    .orElseThrow().last();
            footer.append("Newest shown ").append(iso(newest, zone)).append(". Newer: repeat with start=\"")
                    .append(iso(newest.truncatedTo(ChronoUnit.MILLIS), zone)).append("\"; boundary lines may repeat. ");
        } else {
            Instant oldest = shownRows.stream().filter(row -> row.lines() > 0).findFirst().orElseThrow().first();
            footer.append("Oldest shown ").append(iso(oldest, zone)).append(". Older: repeat with end=\"")
                    .append(iso(QueryTime.ceilMillis(oldest), zone)).append("\"; boundary lines may repeat. ");
        }
        footer.append("If the same timestamp fills every page, narrow the query. ")
                .append("For many lines, use countLogs or exportLogs.");
        return footer.toString();
    }

    private record PageRow(String text, Instant first, Instant last, int lines) {
    }

    public String count(String connection, String query, String start, String end, String groupBy) {
        return count(connection, query, start, end, groupBy, null);
    }

    public String count(String connection, String query, String start, String end, String groupBy, String stepText) {
        var definition = registry.require(connection);
        requireLogQuery(query);
        String grouping = groupBy == null ? "" : groupBy.strip();
        String timeLabel = grouping.endsWith(",time") ? grouping.substring(0, grouping.length() - 5).strip() : null;
        long maximum = grouping.equals("time") ? definition.limits().maxTimeCountIntervalSeconds()
                : definition.limits().maxCountIntervalSeconds();
        var range = QueryTime.range(start, end, clock.instant(), definition.timezone(), maximum);
        ZoneId zone = definition.timezone();
        String where = query.strip() + " in " + window(range, zone) + " (" + connection + ")";
        if (stepText != null && !stepText.isBlank() && !grouping.equals("time") && timeLabel == null) {
            throw Errors.invalid("step requires groupBy=\"time\" or groupBy=\"<label>,time\".");
        }
        if (timeLabel != null && !timeLabel.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw Errors.invalid("groupBy must be a label name, \"time\", or \"<label>,time\".");
        }
        if (groupBy == null || groupBy.isBlank()) {
            long total = sum(vector(client.queryInstant(connection, countExpression(query, range.duration(), null), range.end())));
            return total + " lines match " + where + ".";
        }
        if (grouping.equals("time") || timeLabel != null) {
            return buckets(connection, query, range, zone, timeLabel, stepText,
                    definition.limits().maxEntries(), definition.limits().maxResponseBytes() - ENVELOPE_BYTES);
        }
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

    private static Duration bucketStep(String text, Duration window) {
        if (text == null || text.isBlank()) return niceStep(window, TIME_BUCKETS);
        var matcher = STEP.matcher(text.strip());
        if (!matcher.matches()) throw Errors.invalid("step must be 1s through 1d, e.g. \"1h\" or \"1d\".");
        try {
            long amount = Long.parseLong(matcher.group(1));
            Duration step = switch (matcher.group(2)) {
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                default -> Duration.ofDays(amount);
            };
            if (step.compareTo(Duration.ofDays(1)) <= 0) return step;
        } catch (NumberFormatException | ArithmeticException ignored) {
            // An oversized step has the same actionable error as an out-of-range one.
        }
        throw Errors.invalid("step must be 1s through 1d, e.g. \"1h\" or \"1d\".");
    }

    private String buckets(String connection, String query, QueryTime.Range range, ZoneId zone,
                           String label, String stepText, int maxEntries, int budget) {
        Duration step = bucketStep(stepText, range.duration());
        if (range.duration().dividedBy(step) > MAX_TIME_BUCKETS - 2) {
            throw Errors.invalid("step creates too many buckets; use a larger step or a shorter window.");
        }
        long seconds = step.toSeconds();
        Instant first = Instant.ofEpochSecond(Math.floorDiv(range.start().getEpochSecond(), seconds)
                * seconds + seconds);
        long endSecond = range.end().getEpochSecond() + (range.end().getNano() > 0 ? 1 : 0);
        Instant last = Instant.ofEpochSecond(Math.floorDiv(endSecond + seconds - 1, seconds) * seconds);
        if (!first.isBefore(last)) last = first.plus(step);
        var response = client.queryRange(connection, countExpression(query, step, label), first, last,
                maxEntries, LokiHttpClient.Direction.FORWARD,
                BigDecimal.valueOf(step.toMillis(), 3));
        if (!(response.data() instanceof LokiResponses.Matrix matrix)) throw notMetric();
        if (label != null) return groupedBuckets(query, connection, zone, step, first, last,
                matrix, label, budget);
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
        var rows = new ArrayList<BucketRow>();
        for (var bucket : counts.entrySet()) {
            rows.add(new BucketRow(bucket.getKey(), null, bucket.getValue()));
        }
        return renderBuckets(total, where, step, zone, null, rows, 0, budget);
    }

    private String groupedBuckets(String query, String connection, ZoneId zone,
                                  Duration step, Instant first, Instant last, LokiResponses.Matrix matrix,
                                  String label, int budget) {
        var groups = new TreeMap<String, TreeMap<Long, Long>>();
        for (var series : matrix.series()) {
            String value = series.labels().getOrDefault(label, "");
            var counts = groups.computeIfAbsent(value, ignored -> new TreeMap<>());
            for (var sample : series.samples()) {
                long at = sample.timestampSeconds().setScale(0, RoundingMode.HALF_UP).longValueExact();
                if (at >= first.getEpochSecond() && at <= last.getEpochSecond()) {
                    counts.merge(at, value(sample.value()), Long::sum);
                }
            }
        }
        var ranked = new ArrayList<>(groups.entrySet());
        ranked.sort(Comparator.<Map.Entry<String, TreeMap<Long, Long>>>comparingLong(
                entry -> entry.getValue().values().stream().mapToLong(Long::longValue).sum())
                .reversed().thenComparing(Map.Entry::getKey));
        long total = ranked.stream().flatMap(entry -> entry.getValue().values().stream())
                .mapToLong(Long::longValue).sum();
        var rows = new ArrayList<BucketRow>();
        for (int i = 0; i < Math.min(DEFAULT_LIMIT, ranked.size()); i++) {
            var group = ranked.get(i);
            for (var count : group.getValue().entrySet()) {
                rows.add(new BucketRow(count.getKey(), group.getKey(), count.getValue()));
            }
        }
        rows.sort(Comparator.comparingLong(BucketRow::evaluationSecond).thenComparing(BucketRow::label));
        String where = query.strip() + " in " + window(new QueryTime.Range(first.minus(step), last), zone)
                + " (" + connection + ")";
        return renderBuckets(total, where, step, zone, label, rows,
                Math.max(0, ranked.size() - DEFAULT_LIMIT), budget);
    }

    private static String renderBuckets(long total, String where, Duration step, ZoneId zone, String label,
                                        List<BucketRow> rows, int hiddenValues, int budget) {
        String header = total + " lines match " + where + ".\nBy "
                + (label == null ? "time" : label + " and time") + " (" + QueryTime.lokiDuration(step)
                + " buckets, bucket start" + (hiddenValues > 0 ? ", top 50 values" : "") + "):";
        int shown = Math.min(rows.size(), MAX_BUCKET_ROWS);
        while (true) {
            var text = new StringBuilder(header);
            LocalDate previousDay = null;
            DateTimeFormatter format = step.toSeconds() < 60 ? DateTimeFormatter.ofPattern("HH:mm:ss")
                    : DateTimeFormatter.ofPattern("HH:mm");
            for (int i = 0; i < shown; i++) {
                BucketRow row = rows.get(i);
                var bucketStart = Instant.ofEpochSecond(row.evaluationSecond()).minus(step).atZone(zone);
                LocalDate day = bucketStart.toLocalDate();
                if (previousDay != null && !day.equals(previousDay)) text.append("\n--- ").append(day).append(" ---");
                previousDay = day;
                text.append("\n  ").append(format.format(bucketStart));
                if (label != null) text.append("  ").append(row.label().isEmpty() ? "(none)" : row.label());
                text.append(String.format("  %6d", row.count()));
            }
            if (shown < rows.size() || hiddenValues > 0) {
                text.append("\nOutput limit reached: showing ").append(shown)
                        .append(" of ").append(rows.size()).append(" rows");
                if (hiddenValues > 0) text.append("; +").append(hiddenValues).append(" more values");
                text.append("; total includes hidden counts.");
            }
            if (bytes(text) <= budget) return text.toString();
            if (shown == 0) {
                throw Errors.failure(ErrorCode.RESPONSE_BUDGET_EXCEEDED,
                        "Bucket output does not fit maxResponseBytes of this connection. "
                                + "Use a larger step or narrower query.");
            }
            shown--;
        }
    }

    private record BucketRow(long evaluationSecond, String label, long count) {
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
