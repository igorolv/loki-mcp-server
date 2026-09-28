package ru.it_spectrum.ai.loki.mcp.service;

import ru.it_spectrum.ai.loki.mcp.model.LogEvent;
import ru.it_spectrum.ai.loki.mcp.parser.NormalizedLogLine;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static ru.it_spectrum.ai.loki.mcp.service.ResponseText.labels;
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

    /**
     * One event: {@code HH:mm:ss.SSS LEVEL service  message}, then indented compact stack trace lines.
     * Raw preview: {@code HH:mm:ss.SSS {stream labels}  returned line}, shortened at {@link #RAW_CHARS} code points.
     */
    public static String line(LogEvent event, NormalizedLogLine view, ZoneId zone, boolean raw) {
        var text = new StringBuilder(TIME.format(QueryTime.fromNanos(event.timestampNanos()).atZone(zone)));
        if (raw)
            return text.append(' ').append(labels(event.labels())).append("  ").append(truncate(event.line(), RAW_CHARS)).toString();
        text.append(' ').append(String.format("%-5s", view.level() == null ? "-" : view.level()));
        text.append(' ').append(view.service() == null ? "-" : view.service()).append("  ");
        String message = view.message();
        if (message.isEmpty() && view.format() == NormalizedLogLine.Format.PLAIN
                && view.fields().containsKey("message")) {
            message = view.logger() == null ? "(empty message)" : "(empty message, logger " + view.logger() + ")";
        }
        text.append(truncate(message.replace('\n', ' ').replace("\r", ""), MESSAGE_CHARS));
        if (view.traceId() != null) text.append(" [trace=").append(truncate(view.traceId(), 16)).append(']');
        if (view.stackTrace() != null && !view.stackTrace().isBlank()) {
            for (String frame : compactStackTrace(view.stackTrace())) text.append("\n    ").append(frame);
        }
        return text.toString();
    }

    public static String frameSummary(LogEvent event, String service, int count, ZoneId zone) {
        return TIME.format(QueryTime.fromNanos(event.timestampNanos()).atZone(zone))
                + " " + String.format("%-5s", "-") + " " + (service == null ? "-" : service)
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

    /**
     * Inserts a date marker where consecutive chronological events cross midnight.
     */
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
}
