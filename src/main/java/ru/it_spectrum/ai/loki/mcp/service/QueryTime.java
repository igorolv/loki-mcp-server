package ru.it_spectrum.ai.loki.mcp.service;

import java.math.BigInteger;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/** Every relative endpoint of one operation uses the same clock reading. */
public final class QueryTime {
    private static final Pattern RELATIVE = Pattern.compile("(?:now-)?([1-9][0-9]*)(ns|ms|s|m|h|d)");
    private static final Pattern DURATION = Pattern.compile("([1-9][0-9]*)(ms|s|m|h|d)");
    public static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");
    public static final String FORMATS = "Use \"now\", \"now-15m\" (ns/ms/s/m/h/d), RFC3339 like \"2026-09-13T10:00:00+03:00\", "
            + "local time \"2026-09-13T10:00:00\" in the connection timezone, or epoch nanoseconds.";
    private QueryTime() {}
    public record Range(Instant start, Instant end) {
        public Duration duration() { return Duration.between(start, end); }
    }
    /** Blank endpoints take the defaults now-1h and now. */
    public static Range range(String start, String end, Instant now, ZoneId zone, long maximumSeconds) {
        Instant a = parse(start == null || start.isBlank() ? "now-1h" : start, now, zone);
        Instant b = parse(end == null || end.isBlank() ? "now" : end, now, zone);
        if (!a.isBefore(b)) throw Errors.invalid("start must be before end. " + FORMATS);
        if (Duration.between(a, b).compareTo(Duration.ofSeconds(maximumSeconds)) > 0)
            throw Errors.invalid("Window is longer than this connection allows (" + Duration.ofSeconds(maximumSeconds) + "). Narrow start/end.");
        return new Range(a, b);
    }
    public static Instant parse(String value, Instant now, ZoneId zone) {
        try {
            if (value == null || value.isBlank()) throw Errors.invalid("Time is empty. " + FORMATS);
            String text = value.strip();
            Instant result;
            var relative = RELATIVE.matcher(text);
            if (text.equals("now")) result = now;
            else if (relative.matches()) result = now.minus(duration(Long.parseLong(relative.group(1)), relative.group(2)));
            else if (text.matches("-?[0-9]+")) {
                long ns = Long.parseLong(text);
                result = Instant.ofEpochSecond(Math.floorDiv(ns, 1_000_000_000), Math.floorMod(ns, 1_000_000_000));
            } else {
                try { result = OffsetDateTime.parse(text).toInstant(); }
                catch (DateTimeException ignored) {
                    var local = LocalDateTime.parse(text);
                    var offsets = zone.getRules().getValidOffsets(local);
                    if (offsets.size() != 1) throw Errors.invalid("Local time is ambiguous or missing (DST); pass an RFC3339 time with offset.");
                    result = local.toInstant(offsets.getFirst());
                }
            }
            nanos(result);
            return result;
        } catch (LokiOperationException safe) {
            throw safe;
        } catch (DateTimeException | ArithmeticException | NumberFormatException ignored) {
            throw Errors.invalid("Cannot parse time \"" + value + "\". " + FORMATS);
        }
    }
    /** Durations like 30s, 5m, 2h, 1d. */
    public static Duration duration(String value) {
        var matcher = value == null ? null : DURATION.matcher(value.strip());
        if (matcher == null || !matcher.matches()) throw Errors.invalid("Cannot parse duration \"" + value + "\". Use ms, s, m, h or d, e.g. \"5m\".");
        return duration(Long.parseLong(matcher.group(1)), matcher.group(2));
    }
    private static Duration duration(long amount, String unit) {
        return switch (unit) {
            case "ns" -> Duration.ofNanos(amount);
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            default -> Duration.ofDays(amount);
        };
    }
    /** Loki duration literal: whole seconds, or milliseconds below one second. */
    public static String lokiDuration(Duration duration) {
        long ms = duration.toMillis();
        return ms % 1000 == 0 ? (ms / 1000) + "s" : ms + "ms";
    }
    public static String nanos(Instant time) {
        try {
            return Long.toString(BigInteger.valueOf(time.getEpochSecond()).multiply(BigInteger.valueOf(1_000_000_000))
                    .add(BigInteger.valueOf(time.getNano())).longValueExact());
        } catch (ArithmeticException ignored) { throw Errors.invalid("Time is out of the supported range. " + FORMATS); }
    }
    public static Instant fromNanos(String nanos) {
        long ns = Long.parseLong(nanos);
        return Instant.ofEpochSecond(Math.floorDiv(ns, 1_000_000_000), Math.floorMod(ns, 1_000_000_000));
    }
    public static String iso(Instant time, ZoneId zone) { return ISO.format(time.atZone(zone)); }
    /** Next millisecond boundary at or after the instant: an exclusive end that never drops sub-millisecond neighbours. */
    public static Instant ceilMillis(Instant time) {
        Instant floor = time.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        return floor.equals(time) ? time : floor.plusMillis(1);
    }
}
