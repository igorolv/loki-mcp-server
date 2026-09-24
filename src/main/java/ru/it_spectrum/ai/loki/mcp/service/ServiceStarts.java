package ru.it_spectrum.ai.loki.mcp.service;

import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;
import ru.it_spectrum.ai.loki.mcp.model.LogEvent;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ru.it_spectrum.ai.loki.mcp.service.LogText.TIME;

/**
 * Service starts, unfinished starts and graceful stops read from Spring Boot lines, with the version every such line
 * carries. A start is a {@code Starting X ... using Java} / {@code Started X in N seconds} pair in one stream (a
 * restarted pod is a new stream); a deploy is a start whose version differs from the previous start of the same
 * service or from the stopped pod it replaced. The version comes from the line's own JSON, so no scan of the window
 * is needed.
 */
final class ServiceStarts {
    /**
     * Appended to a stream selector. The literal alternation runs in Loki as a substring search (4 s over a day of a busy
     * stand on Loki 2.6.1, where the regular expression alone took 18 s); the second stage keeps the exact lines.
     */
    static final String FILTER = " |~ \"Start|Graceful shutdown complete\""
            + " |~ `Started \\S+ in \\S+ seconds|Starting \\S+ (v\\S+ )?using Java|Graceful shutdown complete`";
    static final int SERVICES_SHOWN = 10;
    static final int TIMES_SHOWN = 4;
    static final int DEPLOYS_SHOWN = 2;
    static final int LONG_VERSION = 16;
    static final int SHORT_VERSION = 10;
    /**
     * A stop this close to a start of the same service belongs to that start (rolling update or recreate).
     */
    static final Duration REPLACE = Duration.ofMinutes(10);
    private static final Pattern STARTED = Pattern.compile("\\bStarted \\S+ in [\\d.]+ seconds(?: \\((?:process|JVM) running for ([\\d.]+)\\))?");
    private static final Pattern STARTING = Pattern.compile("\\bStarting \\S+ (?:v(\\S+) )?using Java");
    private static final Pattern STOPPED = Pattern.compile("\\bGraceful shutdown complete\\b");
    private static final Pattern MATCHER = Pattern.compile(DiscoveryService.MATCHER);
    private final String selector;
    private final ErrorCode failure;
    private final boolean cut;
    private final int lines;
    private final Instant windowEnd;
    private final List<Start> starts = new ArrayList<>();
    private final List<Stop> stops = new ArrayList<>();
    private final Map<String, Integer> whileStarting = new HashMap<>();
    private ServiceStarts(String selector, ErrorCode failure, boolean cut, int lines, Instant windowEnd) {
        this.selector = selector;
        this.failure = failure;
        this.cut = cut;
        this.lines = lines;
        this.windowEnd = windowEnd;
    }

    /**
     * The stream selector of a log query without level matchers (start lines are INFO), or null when the query does
     * not start with a plain selector or nothing but level matchers is left.
     */
    static String selector(String query) {
        if (query == null) return null;
        var selector = DiscoveryService.SELECTOR.matcher(query);
        if (!selector.lookingAt()) return null;
        var kept = new ArrayList<String>();
        Matcher matcher = MATCHER.matcher(query.substring(0, selector.end()));
        while (matcher.find()) {
            String text = matcher.group();
            if (!EventNormalizer.LEVEL_LABELS.contains(text.split("[\\s=!~]", 2)[0])) kept.add(text);
        }
        if (kept.isEmpty()) return null;
        return "{" + String.join(", ", kept) + "}";
    }

    static ServiceStarts failed(String selector, ErrorCode code) {
        return new ServiceStarts(selector, code, false, 0, null);
    }

    /**
     * Chronological lines returned for {@code selector + FILTER}; {@code cut} when Loki returned as many as asked for.
     */
    static ServiceStarts of(String selector, List<LogEvent> events, boolean cut, EventNormalizer normalizer,
                            List<String> serviceLabels, Map<String, String> versionFields, Instant windowEnd) {
        var result = new ServiceStarts(selector, null, cut, events.size(), windowEnd);
        var byStream = new LinkedHashMap<Map<String, String>, List<Mark>>();
        for (var event : events) {
            var mark = mark(event, normalizer, serviceLabels, versionFields);
            if (mark != null) byStream.computeIfAbsent(mark.stream(), k -> new ArrayList<>()).add(mark);
        }
        for (var marks : byStream.values()) {
            Mark pending = null;
            for (var mark : marks) {
                switch (mark.kind()) {
                    case STARTING -> {
                        if (pending != null)
                            result.starts.add(new Start(pending.service(), pending.stream(), pending.at(), null, pending.version()));
                        pending = mark;
                    }
                    case STARTED -> {
                        // Without its Starting line (before the window) the process uptime printed by Spring Boot gives the beginning.
                        Instant begin = pending != null ? pending.at() : mark.running() != null ? mark.at().minus(mark.running()) : mark.at();
                        Version version = mark.version() != null ? mark.version() : pending != null ? pending.version() : null;
                        result.starts.add(new Start(mark.service(), mark.stream(), begin, mark.at(), version));
                        pending = null;
                    }
                    case STOPPED ->
                            result.stops.add(new Stop(mark.service(), mark.stream(), mark.at(), mark.version()));
                }
            }
            if (pending != null)
                result.starts.add(new Start(pending.service(), pending.stream(), pending.at(), null, pending.version()));
        }
        result.starts.sort(Comparator.comparing(Start::begin));
        return result;
    }

    static Mark mark(LogEvent event, EventNormalizer normalizer, List<String> serviceLabels, Map<String, String> versionFields) {
        var values = new LinkedHashMap<String, String>();
        normalizer.parse(event.line(), values, new LinkedHashMap<>());
        String message = values.isEmpty() ? event.line() : EventNormalizer.first(values, List.of("message", "msg", "@message", "@m"));
        if (message == null) return null;
        Kind kind;
        String appVersion = null;
        Duration running = null;
        Matcher matcher;
        if ((matcher = STARTED.matcher(message)).find()) {
            kind = Kind.STARTED;
            if (matcher.group(1) != null)
                running = Duration.ofMillis(new BigDecimal(matcher.group(1)).movePointRight(3).longValue());
        } else if ((matcher = STARTING.matcher(message)).find()) {
            kind = Kind.STARTING;
            appVersion = matcher.group(1);
        } else if (STOPPED.matcher(message).find()) kind = Kind.STOPPED;
        else return null;
        return new Mark(kind, QueryTime.fromNanos(event.timestampNanos()), event.labels(), service(event, values, serviceLabels),
                version(values, appVersion, versionFields), running);
    }

    /**
     * {@code sbp-ui-backend [sbp-main]}: the service named in the line and, when it differs, the service label of the
     * stream, which on a shared release label (one helm release, several services) alone is ambiguous.
     */
    static String service(LogEvent event, Map<String, String> values, List<String> serviceLabels) {
        String label = EventNormalizer.first(event.labels(), serviceLabels);
        String named = EventNormalizer.first(values, EventNormalizer.SERVICE_FIELDS);
        if (named == null) return label == null ? "-" : label;
        return label == null || label.equals(named) ? named : named + " [" + label + "]";
    }

    /**
     * The values of the profile's version fields the line holds, a long one (a commit hash) cut to its first 10
     * characters; else the {@code v1.0.4} of a Starting line.
     */
    static Version version(Map<String, String> values, String appVersion, Map<String, String> versionFields) {
        var parts = new ArrayList<Part>();
        for (var field : versionFields.entrySet()) {
            String value = present(values.get(field.getKey()));
            if (value == null) continue;
            if (value.length() > LONG_VERSION) value = value.substring(0, SHORT_VERSION);
            parts.add(new Part(field.getValue(), value));
        }
        if (parts.isEmpty()) return appVersion == null ? null : new Version(List.of(), appVersion);
        return new Version(parts, null);
    }

    private static String present(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * The start whose stream logged this line while starting (between Starting and Started, or after an unfinished
     * Starting), else null.
     */
    Start startOf(LogEvent event) {
        if (starts.isEmpty()) return null;
        for (int i = starts.size() - 1; i >= 0; i--) if (starts.get(i).contains(event, windowEnd)) return starts.get(i);
        return null;
    }

    /**
     * Counts, per service, the sampled lines logged while it was starting.
     */
    void count(List<LogEvent> sample) {
        for (var event : sample) {
            var start = startOf(event);
            if (start != null) whileStarting.merge(start.service(), 1, Integer::sum);
        }
    }

    boolean isEmpty() {
        return failure == null && starts.isEmpty() && stops.isEmpty();
    }

    int services() {
        return summaries().size();
    }

    /**
     * The block for {@code keep} services: a header, one line per service, and the rest in one line.
     */
    List<String> render(int keep, ZoneId zone) {
        var lines = new ArrayList<String>();
        if (failure != null) {
            lines.add("Restarts and deploys: not checked, the query for start and stop lines of " + selector + " failed (" + failure + ").");
            return lines;
        }
        var summaries = summaries();
        lines.add("Restarts and deploys in the window (Spring Boot start and graceful stop lines of " + selector
                + (cut ? "; the newest " + this.lines + " such lines only" : "") + "):");
        for (var summary : summaries.subList(0, Math.min(keep, summaries.size()))) lines.add(summary.render(zone));
        if (summaries.size() > keep) {
            var names = new ArrayList<String>();
            for (var summary : summaries.subList(keep, summaries.size())) names.add(summary.name);
            lines.add("  (+" + names.size() + " more services: " + LogText.truncate(String.join(", ", names), 200) + ")");
        }
        return lines;
    }

    /**
     * Services with sampled lines logged while starting first, then those with deploys, unfinished starts or lone
     * stops, then the most recent.
     */
    private List<Summary> summaries() {
        var byName = new LinkedHashMap<String, Summary>();
        for (var start : starts) byName.computeIfAbsent(start.service(), Summary::new).starts.add(start);
        for (var stop : stops) byName.computeIfAbsent(stop.service(), Summary::new).stops.add(stop);
        var result = new ArrayList<>(byName.values());
        for (var summary : result) {
            summary.stops.sort(Comparator.comparing(Stop::at));
            summary.whileStarting = whileStarting.getOrDefault(summary.name, 0);
            summary.analyse();
        }
        result.sort(Comparator.<Summary>comparingInt(s -> s.whileStarting).reversed()
                .thenComparing(s -> !s.notable())
                .thenComparing(s -> s.latest, Comparator.reverseOrder()));
        return result;
    }

    enum Kind {STARTING, STARTED, STOPPED}

    record Mark(Kind kind, Instant at, Map<String, String> stream, String service, Version version, Duration running) {
    }

    /**
     * {@code end} is null for a start without its {@code Started} line in the window.
     */
    record Start(String service, Map<String, String> stream, Instant begin, Instant end, Version version) {
        boolean contains(LogEvent event, Instant windowEnd) {
            Instant at = QueryTime.fromNanos(event.timestampNanos());
            if (at.isBefore(begin) || at.isAfter(end == null ? windowEnd : end)) return false;
            for (var label : stream.entrySet())
                if (!label.getValue().equals(event.labels().get(label.getKey()))) return false;
            return true;
        }
    }

    record Stop(String service, Map<String, String> stream, Instant at, Version version) {
    }

    record Deploy(Instant at, Version before, Version after) {
    }

    /**
     * One version field as printed: {@code build 2790}, or the value alone when the profile gives no word.
     */
    record Part(String label, String value) {
        @Override
        public String toString() {
            return label.isEmpty() ? value : label + " " + value;
        }
    }

    /**
     * The version fields of a line in the profile's order, or the {@code v1.0.4} of a Starting line ({@code app}).
     */
    record Version(List<Part> parts, String app) {
        Version {
            parts = List.copyOf(parts);
        }

        private static void change(List<String> parts, String name, String before, String after) {
            if (!Objects.equals(before, after))
                parts.add(name + (before == null ? "(none)" : before) + " → " + (after == null ? "(none)" : after));
        }

        private static String valueOf(List<Part> parts, String label) {
            for (var part : parts) if (part.label().equals(label)) return part.value();
            return null;
        }

        /**
         * {@code main build 2790 commit c000000002}.
         */
        @Override
        public String toString() {
            var text = new ArrayList<String>();
            for (var part : parts) text.add(part.toString());
            if (app != null) text.add("v" + app);
            return String.join(" ", text);
        }

        /**
         * {@code build 2790 → 2791, commit c000000002 → c000000003}: only the parts that changed.
         */
        String changeFrom(Version before) {
            var labels = new LinkedHashSet<String>();
            for (var part : parts) labels.add(part.label());
            for (var part : before.parts) labels.add(part.label());
            var text = new ArrayList<String>();
            for (String label : labels)
                change(text, label.isEmpty() ? "" : label + " ", valueOf(before.parts, label), valueOf(parts, label));
            change(text, "", before.app == null ? null : "v" + before.app, app == null ? null : "v" + app);
            return String.join(", ", text);
        }
    }

    private final class Summary {
        final String name;
        final List<Start> starts = new ArrayList<>();
        final List<Stop> stops = new ArrayList<>();
        final List<Deploy> deploys = new ArrayList<>();
        final List<Stop> alone = new ArrayList<>();
        int whileStarting;
        Instant latest = Instant.MIN;

        Summary(String name) {
            this.name = name;
        }

        void analyse() {
            Version previous = null;
            var replaced = new HashSet<Stop>();
            for (var start : starts) {
                Instant at = start.end() == null ? start.begin() : start.end();
                if (at.isAfter(latest)) latest = at;
                Stop stopped = null;
                for (var stop : stops)
                    if (!stop.stream().equals(start.stream()) && !replaced.contains(stop)
                            && !stop.at().isBefore(start.begin().minus(REPLACE)) && !stop.at().isAfter(at.plus(REPLACE))) {
                        stopped = stop;
                        break;
                    }
                if (stopped != null) replaced.add(stopped);
                Version before = previous != null ? previous : stopped != null ? stopped.version() : null;
                if (before != null && start.version() != null && !before.equals(start.version()))
                    deploys.add(new Deploy(at, before, start.version()));
                if (start.version() != null) previous = start.version();
            }
            for (var stop : stops) {
                if (stop.at().isAfter(latest)) latest = stop.at();
                if (!replaced.contains(stop)) alone.add(stop);
            }
        }

        boolean notable() {
            return !deploys.isEmpty() || !alone.isEmpty() || starts.stream().anyMatch(s -> s.end() == null);
        }

        String render(ZoneId zone) {
            var text = new StringBuilder("  ").append(name).append("  ");
            var parts = new ArrayList<String>();
            var finished = starts.stream().filter(s -> s.end() != null).toList();
            if (!finished.isEmpty()) {
                var times = new ArrayList<String>();
                for (var start : finished.subList(Math.max(0, finished.size() - TIMES_SHOWN), finished.size()))
                    times.add(TIME.format(start.end().atZone(zone)));
                parts.add("started " + (finished.size() > TIMES_SHOWN ? finished.size() + " times, last " : "") + String.join(", ", times));
            }
            Version version = null;
            for (var start : starts) if (start.version() != null) version = start.version();
            if (deploys.isEmpty()) {
                // "unchanged" only when every finished start printed the same version; an unknown one proves nothing.
                Version shown = version;
                boolean same = shown != null && finished.size() > 1 && finished.stream().allMatch(s -> shown.equals(s.version()));
                if (version != null && !finished.isEmpty())
                    parts.add("version " + version + (same ? ", unchanged" : ""));
            } else {
                var shown = new ArrayList<String>();
                for (var deploy : deploys.subList(Math.max(0, deploys.size() - DEPLOYS_SHOWN), deploys.size()))
                    shown.add(TIME.format(deploy.at().atZone(zone)) + " " + deploy.after().changeFrom(deploy.before()));
                parts.add((deploys.size() == 1 ? "deploy at " : deploys.size() + " deploys, last " + (shown.size() == 1 ? "" : shown.size() + " ")
                                                                + "at ") + String.join("; ", shown));
                parts.add("now " + version);
            }
            for (var start : starts)
                if (start.end() == null) parts.add("start at " + TIME.format(start.begin().atZone(zone))
                        + " did not finish (no \"Started\" line after it in its stream)");
            for (var stop : alone)
                parts.add("stopped at " + TIME.format(stop.at().atZone(zone)) + " with no start nearby");
            if (whileStarting > 0)
                parts.add(whileStarting + (whileStarting == 1 ? " sampled line" : " sampled lines") + " logged while starting");
            return text.append(String.join("; ", parts)).toString();
        }
    }
}
