package ru.it_spectrum.ai.loki.mcp.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.client.LokiResponses;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.error.ErrorCode;
import ru.it_spectrum.ai.loki.mcp.error.Errors;
import ru.it_spectrum.ai.loki.mcp.error.LokiOperationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static ru.it_spectrum.ai.loki.mcp.service.ResponseText.ENVELOPE_BYTES;
import static ru.it_spectrum.ai.loki.mcp.service.ResponseText.bytes;
import static ru.it_spectrum.ai.loki.mcp.service.ResponseText.window;

/**
 * Loki-side line counts and grouped time buckets rendered as text.
 */
@Service
public class CountService {
    public static final int TIME_BUCKETS = 12;
    private static final int DEFAULT_GROUP_LIMIT = 50;
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
    public CountService(ConnectionRegistry registry, LokiHttpClient client) {
        this(registry, client, Clock.systemUTC());
    }

    public CountService(ConnectionRegistry registry, LokiHttpClient client, Clock clock) {
        this.registry = registry;
        this.client = client;
        this.clock = clock;
    }

    public String count(String connection, String query, String start, String end, String groupBy) {
        return count(connection, query, start, end, groupBy, null);
    }

    public String count(String connection, String query, String start, String end, String groupBy, String stepText) {
        var definition = registry.require(connection);
        LogQueries.requireLogQuery(query);
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
        for (int i = 0; i < Math.min(DEFAULT_GROUP_LIMIT, rows.size()); i++) {
            var row = rows.get(i);
            text.append("\n  ").append(String.format("%-" + width + "s  %d",
                    row.getKey().isEmpty() ? "(none)" : row.getKey(), row.getValue()));
        }
        if (rows.size() > DEFAULT_GROUP_LIMIT) text.append("\n  (+").append(rows.size() - DEFAULT_GROUP_LIMIT).append(" more values)");
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
        for (int i = 0; i < Math.min(DEFAULT_GROUP_LIMIT, ranked.size()); i++) {
            var group = ranked.get(i);
            for (var count : group.getValue().entrySet()) {
                rows.add(new BucketRow(count.getKey(), group.getKey(), count.getValue()));
            }
        }
        rows.sort(Comparator.comparingLong(BucketRow::evaluationSecond).thenComparing(BucketRow::label));
        String where = query.strip() + " in " + window(new QueryTime.Range(first.minus(step), last), zone)
                + " (" + connection + ")";
        return renderBuckets(total, where, step, zone, label, rows,
                Math.max(0, ranked.size() - DEFAULT_GROUP_LIMIT), budget);
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

}
