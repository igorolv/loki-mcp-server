package ru.it_spectrum.ai.loki.mcp.service;

import ru.it_spectrum.ai.loki.mcp.connection.LogRule;
import ru.it_spectrum.ai.loki.mcp.model.LogEvent;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static ru.it_spectrum.ai.loki.mcp.service.LogText.*;

/**
 * Groups sampled lines locally and without Loki's pattern API, so it works on Loki 2.6.1: a line with a stack trace
 * by its root cause and application frame ({@link ErrorSignature}), any other line by its message template.
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
     * Numbers with dotted parts (versions, IPs), space-grouped thousands (6 029) and a short unit (15ms, 3s, 42MB);
     * "v1.2" and "asva2" keep their digits.
     */
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])[+-]?\\d+(?:[.,]\\d+)*(?: \\d{3}(?!\\d))*[a-zA-Z\u00b5%]{0,2}(?!\\w)");
    /**
     * A summary line is an index into the log, not the log: long SQL or audit payloads are cut shorter than in queryLogs.
     */
    public static final int SUMMARY_MESSAGE_CHARS = 200;
    public static final int ROOT_MESSAGE_CHARS = 160;
    static final int WRAPPERS_SHOWN = 3;
    public static final int NOISE_GROUPS = 10;
    static final int NOISE_MESSAGE_CHARS = 120;
    static final int LINKS_PER_GROUP = 2;
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern FRAME_LINE = Pattern.compile("(?:at \\S.*|\\.\\.\\. \\d+ (?:more|common frames omitted).*)");

    private LogSummary() {
    }

    public static final class Group {
        final String template;
        int count;
        LogEvent first, last;
        EventNormalizer.View lastView;
        ErrorSignature lastSignature;
        LogRules.Match rule;
        List<CorrelationKeys.Key> lastKeys = List.of();
        /**
         * {@code taskExecutionId=13548 → scheduler-main 2 lines}: keys of the newest line found in lines of other groups.
         */
        final List<String> links = new ArrayList<>();

        Group(String template) {
            this.template = template;
        }
    }

    /**
     * Chronological events → groups sorted by count (descending), then by newest occurrence.
     */
    public static List<Group> group(List<LogEvent> events, EventNormalizer normalizer, List<String> serviceLabels,
                                    List<String> applicationPackages) {
        return group(events, normalizer, serviceLabels, applicationPackages, List.of());
    }

    public static List<Group> group(List<LogEvent> events, EventNormalizer normalizer, List<String> serviceLabels,
                                    List<String> applicationPackages, List<LogRule> rules) {
        var groups = new LinkedHashMap<String, Group>();
        var occurrences = new HashMap<CorrelationKeys.Key, List<Group>>();
        for (var event : events) {
            var view = normalizer.view(event, serviceLabels);
            var signature = signature(view, applicationPackages);
            var group = groups.computeIfAbsent(signature != null ? signature.key() : template(view), Group::new);
            if (group.count++ == 0) group.first = event;
            group.last = event;
            group.lastView = view;
            group.lastSignature = signature;
            var match = LogRules.match(rules, view, signature);
            if (match != null) group.rule = match;
            group.lastKeys = CorrelationKeys.of(view);
            for (var key : group.lastKeys) occurrences.computeIfAbsent(key, k -> new ArrayList<>()).add(group);
        }
        for (var group : groups.values()) link(group, occurrences);
        var sorted = new ArrayList<>(groups.values());
        sorted.sort(Comparator.<Group>comparingInt(g -> g.count).reversed().thenComparing(g -> g.last.nanos(), Comparator.reverseOrder()));
        return sorted;
    }

    /**
     * Keys of the group's newest line that lines of other groups carry too, counted by the service of those groups.
     */
    private static void link(Group group, Map<CorrelationKeys.Key, List<Group>> occurrences) {
        for (var key : group.lastKeys) {
            if (group.links.size() == LINKS_PER_GROUP) return;
            var byService = new LinkedHashMap<String, Integer>();
            for (var other : occurrences.getOrDefault(key, List.of())) {
                if (other == group) continue;
                String service = other.lastView.service() == null ? "-" : other.lastView.service();
                byService.merge(service, 1, Integer::sum);
            }
            if (byService.isEmpty()) continue;
            var parts = new ArrayList<String>();
            for (var entry : byService.entrySet())
                parts.add(entry.getKey() + " " + entry.getValue() + (entry.getValue() == 1 ? " line" : " lines"));
            group.links.add(key.text() + " → " + String.join(", ", parts));
        }
    }

    /**
     * The root cause of the line's stack trace, or null when there is no trace or it has no exception header.
     */
    static ErrorSignature signature(EventNormalizer.View view, List<String> applicationPackages) {
        if (view.stackTrace() == null || view.stackTrace().isBlank()) return null;
        if (FRAME_LINE.matcher(view.message().strip()).matches()) return null;
        return ErrorSignature.of(view.stackTrace(), applicationPackages);
    }

    /**
     * The message with identifiers replaced by {@code *}, plus exception headers so that different errors stay apart.
     * Used for lines without a root-cause signature.
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
     * Outermost first, repeats collapsed; a long chain keeps its outermost and the two nearest to the root.
     */
    static String wrappers(List<String> chain) {
        var distinct = new ArrayList<String>();
        for (String type : chain) if (distinct.isEmpty() || !distinct.getLast().equals(type)) distinct.add(type);
        if (distinct.size() <= WRAPPERS_SHOWN) return String.join(", ", distinct);
        return distinct.getFirst() + ", …, " + String.join(", ", distinct.subList(distinct.size() - (WRAPPERS_SHOWN - 1), distinct.size()));
    }

    /**
     * {@code  340×  10:03:12.001–10:59:58.120  ERROR backend  message}, then for a root-cause group the root exception
     * with its wrappers and the application frame of the newest example, otherwise its exception headers.
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
                : truncate(SPACES.matcher(view.message()).replaceAll(" ").strip(), SUMMARY_MESSAGE_CHARS));
        lines.add(text.toString());
        lines.addAll(causeLines(view, group.lastSignature, group.rule, true));
        if (!group.links.isEmpty()) lines.add("         linked: " + String.join("; ", group.links));
        return lines;
    }

    /**
     * The root cause with its wrappers and the application frame, or up to three exception headers when the trace has
     * no typed root, then the rule tag with its advice (the tag alone when the advice was already printed). Indented to
     * sit under a line of text.
     */
    static List<String> causeLines(EventNormalizer.View view, ErrorSignature signature, LogRules.Match rule, boolean advice) {
        var lines = new ArrayList<String>();
        if (signature != null) {
            var root = new StringBuilder("         ").append(signature.rootType());
            // The logged message often already quotes the root message; it is not printed twice.
            if (!signature.rootMessage().isEmpty() && !normalize(view.message()).contains(normalize(signature.rootMessage())))
                root.append(": ").append(truncate(signature.rootMessage(), ROOT_MESSAGE_CHARS));
            if (!signature.wrappers().isEmpty()) root.append("  ← wrapped in ").append(wrappers(signature.wrappers()));
            lines.add(root.toString());
            if (signature.appFrame() != null) lines.add("         at " + signature.appFrame().text());
        } else if (view.stackTrace() != null && !view.stackTrace().isBlank()) {
            int headers = 0;
            for (String line : compactStackTrace(view.stackTrace())) {
                if (line.startsWith("at ") || line.startsWith("... (")) continue;
                lines.add("         " + line);
                if (++headers == 3) break;
            }
        }
        if (rule != null) lines.add("         " + rule.tag() + (advice ? " " + rule.advice() : ""));
        return lines;
    }

    public static boolean isNoise(Group group) {
        return group.rule != null && group.rule.category() == LogRule.Category.NOISE;
    }

    /**
     * One line: {@code  436×  12:18:11.304–00:06:46.360  sec-main  missing-endpoint  <short message>}.
     */
    public static String renderNoise(Group group, ZoneId zone) {
        var view = group.lastView;
        String span = TIME.format(QueryTime.fromNanos(group.first.timestampNanos()).atZone(zone));
        if (group.count > 1) span += "–" + TIME.format(QueryTime.fromNanos(group.last.timestampNanos()).atZone(zone));
        String what = group.lastSignature != null ? group.lastSignature.rootType() + ": " + group.lastSignature.rootMessage() : view.message();
        return String.format("%5d×  ", group.count) + span + "  " + (view.service() == null ? "-" : view.service()) + "  "
                + group.rule.rule().id() + "  " + truncate(SPACES.matcher(what).replaceAll(" ").strip(), NOISE_MESSAGE_CHARS);
    }
}
