package ru.it_spectrum.ai.loki.mcp.service;

import ru.it_spectrum.ai.loki.mcp.model.LogEvent;

import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * What the lines of a summary group have in common that the other lines of their services do not: one pod, one
 * build, one user. The other lines are a background sample of the window (slices over the query's stream selector
 * without its pipeline). Field names come from the lines and labels, never from code; rules and the measurements
 * behind them: docs/decisions.md, "Baseline and field contrast".
 */
final class FieldContrast {
    static final int SLICES = 12;
    static final int SLICE_LINES = 200;
    static final int MIN_LINES = 3;
    /**
     * A field is compared only with background lines that carry it, and only when at least this many do; fewer
     * carriers make it "rare in other lines", which counts only when every line of the group has one value.
     */
    static final int MIN_CARRIERS = 20;
    static final double MIN_SHARE = 0.5;
    static final double MIN_GAP = 0.4;
    static final int FINDINGS = 2;
    static final int PARTS = 4;
    static final int VALUE_CHARS = 40;
    static final int VALUE_MAX = 120;
    /**
     * Labels every promtail stream carries that repeat another label (the file of the pod) or say nothing.
     */
    private static final Set<String> SKIPPED_LABELS = Set.of("filename", "job");
    private static final Set<String> SKIPPED_FIELDS;
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    static {
        var skipped = new HashSet<String>();
        skipped.addAll(EventNormalizer.LEVEL_LABELS);
        skipped.addAll(EventNormalizer.LEVEL_FIELDS);
        skipped.addAll(EventNormalizer.LOGGER_FIELDS);
        skipped.addAll(EventNormalizer.MESSAGE_FIELDS);
        skipped.addAll(EventNormalizer.STACK_FIELDS);
        skipped.addAll(EventNormalizer.TRACE_KEYS);
        skipped.addAll(List.of("@timestamp", "timestamp", "time", "ts", "process.pid", "pid", "ecs.version"));
        SKIPPED_FIELDS = Set.copyOf(skipped);
    }

    private FieldContrast() {
    }

    /**
     * The time slices the background is read in, newest {@link #SLICE_LINES} lines of each.
     */
    static List<QueryTime.Range> slices(QueryTime.Range window) {
        var slices = new ArrayList<QueryTime.Range>();
        Duration step = window.duration().dividedBy(SLICES);
        if (step.isZero()) return List.of(window);
        for (int i = 0; i < SLICES; i++) {
            var start = window.start().plus(step.multipliedBy(i));
            slices.add(new QueryTime.Range(start, i == SLICES - 1 ? window.end() : start.plus(step)));
        }
        return slices;
    }

    /**
     * Stream labels and scalar JSON fields of a line that can tell lines apart: not its time, level, logger, message,
     * error, trace or process id; values up to {@link #VALUE_MAX} characters; digits in thread names folded, since a
     * pool numbers its threads.
     */
    static Map<String, String> fields(LogEvent event, EventNormalizer normalizer) {
        var fields = new TreeMap<String, String>();
        event.labels().forEach((name, value) -> {
            if (!SKIPPED_LABELS.contains(name) && !EventNormalizer.LEVEL_LABELS.contains(name) && value != null && !value.isBlank()
                    && value.length() <= VALUE_MAX) fields.put(name, value);
        });
        var values = new LinkedHashMap<String, String>();
        normalizer.parse(event.line(), values, new HashMap<>());
        values.forEach((name, value) -> {
            if (SKIPPED_FIELDS.contains(name) || name.startsWith("error.") || value.isBlank() || value.length() > VALUE_MAX)
                return;
            fields.putIfAbsent(name, name.toLowerCase(Locale.ROOT).contains("thread") ? DIGITS.matcher(value).replaceAll("*") : value);
        });
        return fields;
    }

    static boolean background(EventNormalizer.View view) {
        return !"ERROR".equals(view.level()) && !"FATAL".equals(view.level()) && (view.stackTrace() == null || view.stackTrace().isBlank());
    }

    /**
     * {@code all 6 lines: pod …, build.version 2799 (7% of other ssj-main lines)}, at most {@link #FINDINGS}; empty when
     * the group is small or its services have too few other lines to compare with.
     *
     * @param sampleValues values of every field in the sampled lines of the window, by service
     */
    static List<String> findings(LogSummary.Group group, EventNormalizer normalizer, List<Line> background,
                                 Map<String, Map<String, Set<String>>> sampleValues) {
        var lines = new ArrayList<String>();
        for (var finding : analyse(group, normalizer, background, sampleValues))
            lines.add("         " + render(finding, group.services, PARTS));
        return lines;
    }

    /**
     * {@code all 6 lines: pod … (7% in other lines of ssj-main), build.version 2799 (43%)}: at most {@code parts} values.
     */
    static String render(Finding finding, Collection<String> services, int parts) {
        var text = new StringBuilder(finding.covered() == finding.size() ? "all " + finding.size() + " lines: "
                : finding.covered() + " of " + finding.size() + " lines: ");
        // Each value with its share among the other lines that carry its field; the first one says what the share is of.
        String others = " other lines of " + String.join(", ", services);
        for (int i = 0; i < Math.min(parts, finding.parts().size()); i++) {
            var part = finding.parts().get(i);
            if (i > 0) text.append(", ");
            text.append(part.name()).append(' ').append(middle(part.value())).append(" (")
                    .append(part.share() < 0 ? "rare" : Math.round(part.share() * 100) + "%").append(i == 0 ? " in" + others : "").append(')');
        }
        if (finding.parts().size() > parts) text.append(" (+").append(finding.parts().size() - parts).append(" more)");
        return text.toString();
    }

    static List<Finding> analyse(LogSummary.Group group, EventNormalizer normalizer, List<Line> background,
                                 Map<String, Map<String, Set<String>>> sampleValues) {
        if (group.events.size() < MIN_LINES) return List.of();
        var services = group.services;
        var others = background.stream().filter(l -> services.contains(l.service())).toList();
        if (others.size() < MIN_CARRIERS) return List.of();
        // Lines of the group holding each field value.
        var byName = new HashMap<String, Map<String, BitSet>>();
        for (int i = 0; i < group.events.size(); i++)
            for (var field : fields(group.events.get(i), normalizer).entrySet()) {
                int line = i;
                byName.computeIfAbsent(field.getKey(), k -> new TreeMap<>()).computeIfAbsent(field.getValue(), k -> new BitSet()).set(line);
            }
        var carriers = new HashMap<String, Integer>();
        var held = new HashMap<String, Integer>();
        for (var line : others)
            for (var field : line.fields().entrySet()) {
                carriers.merge(field.getKey(), 1, Integer::sum);
                held.merge(field.getKey() + "\u0000" + field.getValue(), 1, Integer::sum);
            }
        int size = group.events.size();
        var findings = new LinkedHashMap<BitSet, List<Part>>();
        var gaps = new HashMap<BitSet, Double>();
        for (var name : byName.entrySet()) {
            int carrying = carriers.getOrDefault(name.getKey(), 0);
            for (var value : name.getValue().entrySet()) {
                double share = value.getValue().cardinality() / (double) size;
                double other;
                if (carrying >= MIN_CARRIERS) {
                    other = held.getOrDefault(name.getKey() + "\u0000" + value.getKey(), 0) / (double) carrying;
                    if (share < MIN_SHARE || share - other < MIN_GAP) continue;
                } else {
                    // Rare in other lines: only a value every line of the group holds, of a field that varies in the
                    // sampled and other lines of the same services (a user id does, the build of a service does not).
                    var seen = new HashSet<String>();
                    for (String service : services)
                        seen.addAll(sampleValues.getOrDefault(service, Map.of()).getOrDefault(name.getKey(), Set.of()));
                    for (var line : others)
                        if (line.fields().containsKey(name.getKey())) seen.add(line.fields().get(name.getKey()));
                    if (value.getValue().cardinality() < size || seen.size() < 2) continue;
                    other = -1;
                }
                findings.computeIfAbsent(value.getValue(), k -> new ArrayList<>()).add(new Part(name.getKey(), value.getKey(), other));
                gaps.merge(value.getValue(), share - Math.max(other, 0), Math::max);
            }
        }
        var ordered = new ArrayList<>(findings.entrySet());
        ordered.sort(Comparator.comparingDouble((Map.Entry<BitSet, List<Part>> e) -> -gaps.get(e.getKey()))
                .thenComparingInt(e -> -e.getKey().cardinality()));
        var result = new ArrayList<Finding>();
        for (var finding : ordered.subList(0, Math.min(FINDINGS, ordered.size()))) {
            var parts = new ArrayList<>(finding.getValue());
            parts.sort(Comparator.comparingDouble(Part::share).thenComparing(Part::name));
            result.add(new Finding(finding.getKey().cardinality(), size, List.copyOf(parts)));
        }
        return result;
    }

    /**
     * A long value keeps its start and its end, where pod names and hashes differ.
     */
    static String middle(String value) {
        if (value.length() <= VALUE_CHARS) return value;
        int head = VALUE_CHARS / 3;
        return value.substring(0, head) + "…" + value.substring(value.length() - (VALUE_CHARS - head - 1));
    }

    /**
     * One line of the background: its service and its fields.
     */
    record Line(String service, Map<String, String> fields) {
    }

    /**
     * A field value of a group and its share among the other lines that carry the field; -1 when few other lines do.
     */
    record Part(String name, String value, double share) {
    }

    /**
     * Values held by the same {@code covered} of the group's {@code size} lines, lowest share first.
     */
    record Finding(int covered, int size, List<Part> parts) {
    }
}
