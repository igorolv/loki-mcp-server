package ru.it_spectrum.ai.loki.mcp.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;
import ru.it_spectrum.ai.loki.mcp.model.LogEvent;

/** Renders log lines as text a small model reads directly: time, level, service, message, compact stack trace. */
public final class LogText {
    public static final int MESSAGE_CHARS = 400;
    public static final int RAW_CHARS = 4000;
    public static final int FRAMES = 5;
    /** Reserved for the JSON-RPC envelope, escaping and the request id around the text payload. */
    public static final int ENVELOPE_BYTES = 512;
    public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    public static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private LogText() {}

    /**
     * One event: {@code HH:mm:ss.SSS LEVEL service  message}, then indented compact stack trace lines.
     * Raw: {@code HH:mm:ss.SSS {stream labels}  original line} — the "show everything" mode also shows pod/instance.
     */
    public static String line(LogEvent event, EventNormalizer.View view, ZoneId zone, boolean raw) {
        var text = new StringBuilder(TIME.format(QueryTime.fromNanos(event.timestampNanos()).atZone(zone)));
        if (raw) return text.append(' ').append(labels(event.labels())).append("  ").append(truncate(event.line(), RAW_CHARS)).toString();
        text.append(' ').append(String.format("%-5s", view.level() == null ? "-" : view.level()));
        text.append(' ').append(view.service() == null ? "-" : view.service()).append("  ");
        text.append(truncate(view.message().replace('\n', ' ').replace("\r", ""), MESSAGE_CHARS));
        if (view.traceId() != null) text.append(" [trace=").append(truncate(view.traceId(), 16)).append(']');
        if (view.stackTrace() != null && !view.stackTrace().isBlank()) {
            for (String frame : compactStackTrace(view.stackTrace())) text.append("\n    ").append(frame);
        }
        return text.toString();
    }

    /** Keeps each exception header, its first frames and every "Caused by" with one frame; drops the rest with a count. */
    public static List<String> compactStackTrace(String stack) {
        var result = new ArrayList<String>();
        int kept = 0, skipped = 0, allowed = FRAMES;
        for (String raw : stack.split("\r?\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("...")) continue;
            boolean frame = line.startsWith("at ");
            if (!frame) {
                if (skipped > 0) result.add("... (" + skipped + " frames skipped)");
                skipped = 0; kept = 0;
                boolean cause = line.startsWith("Caused by:") || line.startsWith("Suppressed:");
                allowed = cause ? 1 : FRAMES;
                result.add(truncate(line, MESSAGE_CHARS));
            } else if (kept < allowed) { result.add(line); kept++; }
            else skipped++;
        }
        if (skipped > 0) result.add("... (" + skipped + " frames skipped)");
        return result;
    }

    public static String truncate(String text, int maxChars) {
        if (text.codePointCount(0, text.length()) <= maxChars) return text;
        int end = text.offsetByCodePoints(0, maxChars);
        return text.substring(0, end) + "…";
    }

    public static String labels(Map<String, String> labels) {
        var parts = new ArrayList<String>();
        for (var label : new TreeMap<>(labels).entrySet()) parts.add(label.getKey() + "=\"" + label.getValue() + "\"");
        return "{" + String.join(", ", parts) + "}";
    }

    public static String window(QueryTime.Range range, ZoneId zone) {
        var start = range.start().atZone(zone);
        var end = range.end().atZone(zone);
        String endText = start.toLocalDate().equals(end.toLocalDate()) ? end.format(DateTimeFormatter.ofPattern("HH:mm:ss")) : end.format(DATE_TIME);
        return start.format(DATE_TIME) + "–" + endText + " (" + start.getOffset() + ")";
    }

    /** Inserts a date marker where consecutive chronological events cross midnight. */
    public static List<String> withDateMarkers(List<LogEvent> events, List<String> lines, ZoneId zone) {
        var result = new ArrayList<String>(lines.size());
        LocalDate previous = null;
        for (int i = 0; i < lines.size(); i++) {
            LocalDate day = QueryTime.fromNanos(events.get(i).timestampNanos()).atZone(zone).toLocalDate();
            if (previous != null && !day.equals(previous)) result.add("--- " + day + " ---");
            previous = day;
            result.add(lines.get(i));
        }
        return result;
    }

    public static int bytes(CharSequence text) { return text.toString().getBytes(StandardCharsets.UTF_8).length; }

    /** Drops the oldest lines (front of the list) until header + lines + footer fit the byte budget. */
    public static String fit(String header, List<String> lines, Function<Integer, String> footerForDropped, int budgetBytes) {
        var kept = new ArrayList<>(lines);
        int dropped = 0;
        while (true) {
            String text = assemble(header, kept, footerForDropped.apply(dropped));
            // A page that had lines must keep at least one; an empty page is fine only when there was nothing to show.
            if (bytes(text) <= budgetBytes && (!kept.isEmpty() || lines.isEmpty())) return text;
            if (kept.isEmpty()) throw Errors.failure(ErrorCode.RESPONSE_BUDGET_EXCEEDED,
                    "Even a minimal response does not fit maxResponseBytes of this connection. Narrow the query or raise the limit.");
            kept.removeFirst();
            dropped++;
        }
    }

    public static String assemble(String header, List<String> lines, String footer) {
        var text = new StringBuilder(header);
        for (String line : lines) text.append('\n').append(line);
        if (footer != null && !footer.isEmpty()) text.append('\n').append(footer);
        return text.toString();
    }

    public static String iso(Instant time, ZoneId zone) { return QueryTime.iso(time, zone); }
}
