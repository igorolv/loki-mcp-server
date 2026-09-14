package ru.it_spectrum.ai.loki.mcp.service;

import ru.it_spectrum.ai.loki.mcp.model.LogEvent;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

import static ru.it_spectrum.ai.loki.mcp.service.LogText.*;

/**
 * Groups sampled lines by message template, locally and without Loki's pattern API, so it works on Loki 2.6.1.
 * Counts describe the sample only; the text says so and points to countLogs for the window.
 */
public final class LogSummary {
    public static final int DEFAULT_SAMPLE = 500;
    public static final int TOP_GROUPS = 20;
    public static final int RARE_GROUPS = 20;
    /**
     * A group with at most this many lines is "rare": listed separately so that one-off errors are not lost.
     */
    public static final int RARE_LINES = 2;
    static final String FRAMES_TEMPLATE = "(stack trace frame lines: at ..., ... N more)";
    /**
     * Printed instead of an arbitrary frame: one-line-per-frame services log hundreds of these, and they belong to the
     * exception lines above them, which getLogContext shows.
     */
    static final String FRAMES_EXAMPLE = "stack trace frame lines (at ...); read them with getLogContext around an error line";
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern DATE_TIME_TEXT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{2}:\\d{2}(?::\\d{2}(?:[.,]\\d+)?)?(?:Z|[+-]\\d{2}:?\\d{2})?)?");
    private static final Pattern TIME_TEXT = Pattern.compile("\\b\\d{1,2}:\\d{2}(?::\\d{2}(?:[.,]\\d+)?)?\\b");
    private static final Pattern HEX = Pattern.compile("\\b(?:0x[0-9a-fA-F]+|(?=[0-9a-fA-F]*\\d)[0-9a-fA-F]{8,})\\b");
    /**
     * Numbers with dotted parts (versions, IPs) and a short unit (15ms, 3s, 42MB); "v1.2" and "asva2" keep their digits.
     */
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])[+-]?\\d+(?:[.,]\\d+)*[a-zA-Z\u00b5%]{0,2}(?!\\w)");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern FRAME_LINE = Pattern.compile("(?:at \\S.*|\\.\\.\\. \\d+ (?:more|common frames omitted).*)");

    private LogSummary() {
    }

    public static final class Group {
        final String template;
        int count;
        LogEvent first, last;
        EventNormalizer.View lastView;

        Group(String template) {
            this.template = template;
        }
    }

    /**
     * Chronological events → groups sorted by count (descending), then by newest occurrence.
     */
    public static List<Group> group(List<LogEvent> events, EventNormalizer normalizer, List<String> serviceLabels) {
        var groups = new LinkedHashMap<String, Group>();
        for (var event : events) {
            var view = normalizer.view(event, serviceLabels);
            var group = groups.computeIfAbsent(template(view), Group::new);
            if (group.count++ == 0) group.first = event;
            group.last = event;
            group.lastView = view;
        }
        var sorted = new ArrayList<>(groups.values());
        sorted.sort(Comparator.<Group>comparingInt(g -> g.count).reversed().thenComparing(g -> g.last.nanos(), Comparator.reverseOrder()));
        return sorted;
    }

    /**
     * The message with identifiers replaced by {@code *}, plus exception headers so that different errors stay apart.
     */
    static String template(EventNormalizer.View view) {
        String message = view.message().strip();
        if (FRAME_LINE.matcher(message).matches()) return FRAMES_TEMPLATE;
        var key = new StringBuilder(normalize(message));
        if (view.stackTrace() != null && !view.stackTrace().isBlank()) {
            int headers = 0;
            for (String line : compactStackTrace(view.stackTrace())) {
                if (line.startsWith("at ") || line.startsWith("... (")) continue;
                key.append('\n').append(normalize(line));
                if (++headers == 3) break;
            }
        }
        return key.toString();
    }

    static String normalize(String text) {
        String result = UUID.matcher(text).replaceAll("*");
        result = DATE_TIME_TEXT.matcher(result).replaceAll("*");
        result = TIME_TEXT.matcher(result).replaceAll("*");
        result = HEX.matcher(result).replaceAll("*");
        result = NUMBER.matcher(result).replaceAll("*");
        return SPACES.matcher(result).replaceAll(" ").strip();
    }

    /**
     * {@code  340×  10:03:12.001–10:59:58.120  ERROR backend  message} with exception headers of the newest example below.
     */
    public static List<String> render(Group group, ZoneId zone) {
        var lines = new ArrayList<String>();
        var view = group.lastView;
        String span = TIME.format(QueryTime.fromNanos(group.first.timestampNanos()).atZone(zone));
        if (group.count > 1) span += "–" + TIME.format(QueryTime.fromNanos(group.last.timestampNanos()).atZone(zone));
        var text = new StringBuilder(String.format("%5d×  ", group.count)).append(span).append("  ");
        text.append(String.format("%-5s", view.level() == null ? "-" : view.level()));
        text.append(' ').append(view.service() == null ? "-" : view.service()).append("  ");
        text.append(group.template.equals(FRAMES_TEMPLATE) ? FRAMES_EXAMPLE
                : truncate(view.message().replace('\n', ' ').replace("\r", "").strip(), MESSAGE_CHARS));
        lines.add(text.toString());
        if (view.stackTrace() != null && !view.stackTrace().isBlank()) {
            int headers = 0;
            for (String line : compactStackTrace(view.stackTrace())) {
                if (line.startsWith("at ") || line.startsWith("... (")) continue;
                lines.add("         " + line);
                if (++headers == 3) break;
            }
        }
        return lines;
    }
}
