package ru.it_spectrum.ai.loki.mcp.service;

import ru.it_spectrum.ai.loki.mcp.model.LogEvent;
import ru.it_spectrum.ai.loki.mcp.parser.NormalizedLogEvent;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static ru.it_spectrum.ai.loki.mcp.service.ResponseText.truncate;

/**
 * Renders log lines as text a small model reads directly: time, level, service, message, compact stack trace.
 */
public final class LogText {
    public static final int MESSAGE_CHARS = 400;
    public static final int RAW_CHARS = 4000;
    public static final int FRAMES = 5;
    public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private LogText() {
    }

    private static String labels(Map<String, String> labels) {
        var parts = new ArrayList<String>();
        for (var label : new TreeMap<>(labels).entrySet()) parts.add(label.getKey() + "=\"" + label.getValue() + "\"");
        return "{" + String.join(", ", parts) + "}";
    }

    /**
     * One event: {@code HH:mm:ss.SSS LEVEL service  message}, then indented compact stack trace lines.
     * Raw preview: {@code HH:mm:ss.SSS {stream labels}  returned line}, shortened at {@link #RAW_CHARS} code points.
     */
    public static String line(NormalizedLogEvent normalized, ZoneId zone, boolean raw) {
        LogEvent source = normalized.source();
        var text = new StringBuilder(TIME.format(QueryTime.fromNanos(source.timestampNanos()).atZone(zone)));
        if (raw)
            return text.append(' ').append(labels(source.labels())).append("  ")
                    .append(truncate(source.line(), RAW_CHARS)).toString();
        text.append(' ').append(String.format("%-5s", normalized.level() == null ? "-" : normalized.level()));
        text.append(' ').append(normalized.service() == null ? "-" : normalized.service()).append("  ");
        String message = normalized.message();
        if (message.isEmpty() && normalized.format() == NormalizedLogEvent.Format.PLAIN
                && normalized.fields().containsKey("message")) {
            message = normalized.logger() == null ? "(empty message)"
                    : "(empty message, logger " + normalized.logger() + ")";
        }
        text.append(truncate(message.replace('\n', ' ').replace("\r", ""), MESSAGE_CHARS));
        if (normalized.traceId() != null) text.append(" [trace=").append(truncate(normalized.traceId(), 16)).append(']');
        if (normalized.stackTrace() != null && !normalized.stackTrace().isBlank()) {
            for (String frame : compactStackTrace(normalized.stackTrace())) text.append("\n    ").append(frame);
        }
        return text.toString();
    }

    public static String frameSummary(NormalizedLogEvent normalized, int count, ZoneId zone) {
        return TIME.format(QueryTime.fromNanos(normalized.source().timestampNanos()).atZone(zone))
                + " " + String.format("%-5s", "-") + " "
                + (normalized.service() == null ? "-" : normalized.service())
                + "  … " + count + " stack frame lines";
    }

    /**
     * Keeps each exception header, its first frames and every "Caused by" with one frame; drops the rest with a count.
     */
    public static List<String> compactStackTrace(String stack) {
        var result = new ArrayList<String>();
        int kept = 0, skipped = 0, allowed = FRAMES;
        for (String raw : stack.split("\r?\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("...")) continue;
            boolean frame = line.startsWith("at ");
            if (!frame) {
                if (skipped > 0) result.add("... (" + skipped + " frames skipped)");
                skipped = 0;
                kept = 0;
                boolean cause = line.startsWith("Caused by:") || line.startsWith("Suppressed:");
                allowed = cause ? 1 : FRAMES;
                result.add(truncate(line, MESSAGE_CHARS));
            } else if (kept < allowed) {
                result.add(line);
                kept++;
            } else skipped++;
        }
        if (skipped > 0) result.add("... (" + skipped + " frames skipped)");
        return result;
    }
}
