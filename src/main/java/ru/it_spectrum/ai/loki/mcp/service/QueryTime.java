package ru.it_spectrum.ai.loki.mcp.service;

import java.math.BigInteger;
import java.time.*;
import java.util.regex.Pattern;
import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;
import ru.it_spectrum.ai.loki.mcp.model.QueryResults.Window;

/** No implicit window. Every relative endpoint uses the same operation clock reading. */
public final class QueryTime {
    private static final Pattern RELATIVE = Pattern.compile("now-([1-9][0-9]*)(ns|ms|s|m|h|d)");
    private QueryTime() {}
    public record Range(Instant start, Instant end) {
        public Window model() { return new Window(nanos(start), nanos(end)); }
    }
    public static Range range(String start, String end, Instant now, ZoneId zone, long maximumSeconds) {
        Instant a = parse(start, now, zone), b = parse(end, now, zone);
        require(a.isBefore(b) && Duration.between(a, b).compareTo(Duration.ofSeconds(maximumSeconds)) <= 0);
        return new Range(a, b);
    }
    public static Instant parse(String value, Instant now, ZoneId zone) {
        try {
            require(value != null && !value.isBlank());
            Instant result;
            var relative = RELATIVE.matcher(value);
            if (value.equals("now")) result = now;
            else if (relative.matches()) {
                long amount = Long.parseLong(relative.group(1));
                Duration delta = switch (relative.group(2)) {
                    case "ns" -> Duration.ofNanos(amount);
                    case "ms" -> Duration.ofMillis(amount);
                    case "s" -> Duration.ofSeconds(amount);
                    case "m" -> Duration.ofMinutes(amount);
                    case "h" -> Duration.ofHours(amount);
                    default -> Duration.ofDays(amount);
                };
                result = now.minus(delta);
            } else if (value.matches("-?[0-9]+")) {
                long ns = Long.parseLong(value);
                result = Instant.ofEpochSecond(Math.floorDiv(ns, 1_000_000_000), Math.floorMod(ns, 1_000_000_000));
            } else {
                try { result = OffsetDateTime.parse(value).toInstant(); }
                catch (DateTimeException ignored) {
                    var local = LocalDateTime.parse(value);
                    var offsets = zone.getRules().getValidOffsets(local);
                    require(offsets.size() == 1); // Reject DST gaps and ambiguous local times.
                    result = local.toInstant(offsets.getFirst());
                }
            }
            nanos(result);
            return result;
        } catch (DateTimeException | ArithmeticException | NumberFormatException ignored) {
            throw invalid();
        }
    }
    public static String nanos(Instant time) {
        try {
            return Long.toString(BigInteger.valueOf(time.getEpochSecond()).multiply(BigInteger.valueOf(1_000_000_000))
                    .add(BigInteger.valueOf(time.getNano())).longValueExact());
        } catch (ArithmeticException ignored) { throw invalid(); }
    }
    public static void require(boolean valid) { if (!valid) throw invalid(); }
    public static LokiOperationException invalid() {
        return Errors.failure(ErrorCode.INVALID_ARGUMENT, "Invalid query arguments. Check time formats, mode and configured limits.");
    }
}
