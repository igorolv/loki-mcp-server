package ru.it_spectrum.ai.loki.mcp.service;

import ru.it_spectrum.ai.loki.mcp.connection.LogRule;
import ru.it_spectrum.ai.loki.mcp.model.LogEvent;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

import static ru.it_spectrum.ai.loki.mcp.service.LogText.TIME;
import static ru.it_spectrum.ai.loki.mcp.service.LogText.truncate;

/**
 * The first block of {@code summarizeLogs}: the failures that are new or growing, since when, in which services and in
 * which order, because of what, and whether a service restarted around them. Built from the printed groups after
 * their history ({@link GroupHistory}); rules and measurements: docs/decisions.md, "Incident picture".
 */
final class IncidentPicture {
    static final int INCIDENTS = 5;
    /**
     * The step of the onset counts is about this part of the window.
     */
    static final int TIMELINE_STEPS = 48;
    static final int SERVICES_SHOWN = 4;
    static final int HEADLINE_CHARS = 160;
    static final int FINDING_PARTS = 2;
    static final int RESTART_PHRASES = 3;
    /**
     * A restart this long before the onset may have caused it.
     */
    static final Duration BEFORE = ServiceStarts.REPLACE;
    private static final DateTimeFormatter MINUTES = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MM-dd ");
    /**
     * {@code scheme://[user@]host[:port]}: the user part is never printed.
     */
    private static final Pattern URL = Pattern.compile("\\b[a-zA-Z][a-zA-Z0-9+.-]*://(?:[^@/\\s\"']*@)?([A-Za-z0-9.-]+(?::\\d{1,5})?)");
    private static final Pattern HOST_PORT = Pattern.compile(
            "(?<![\\w.-])((?:[A-Za-z0-9-]+\\.)+[A-Za-z][A-Za-z0-9-]*|\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d{2,5})(?![\\w.])");
    /**
     * {@code File.java:42} of a frame is not an address.
     */
    private static final Set<String> FILE_SUFFIXES = Set.of("java", "kt", "groovy", "scala", "xml", "yml", "yaml", "properties",
            "sql", "class", "jar", "json", "js", "ts", "html");

    private IncidentPicture() {
    }

    /**
     * How the onset was found: from every line of the window, from counts in steps, or from the sample only.
     */
    enum Source {LINES, COUNTS, SAMPLE}

    static final class Incident {
        /**
         * The groups of the incident, most lines first; the new or growing ones among them; the biggest of those.
         */
        final List<LogSummary.Group> groups;
        final List<LogSummary.Group> growing;
        final LogSummary.Group lead;
        final boolean linked;
        Instant onset;
        Source source = Source.LINES;
        Duration step;
        /**
         * Services in the order they began, with the time.
         */
        final Map<String, Instant> services = new LinkedHashMap<>();
        /**
         * The services as start lines name them ({@code ssj-backend [ssj-main]}, see {@link ServiceStarts#service}), oldest
         * line first, with the newest line of each: a release label alone would pick the restarts of its other services.
         */
        final Map<String, Instant> named = new LinkedHashMap<>();
        final Map<String, Instant> last = new HashMap<>();

        Incident(List<LogSummary.Group> groups, List<LogSummary.Group> growing, boolean linked) {
            this.groups = groups;
            this.growing = growing;
            this.linked = linked;
            this.lead = growing.stream().max(Comparator.<LogSummary.Group>comparingInt(g -> g.count)
                    .thenComparing(g -> g.first.nanos(), Comparator.reverseOrder())).orElseThrow();
        }

        int lines() {
            return groups.stream().mapToInt(g -> g.count).sum();
        }

        int weight() {
            return growing.stream().mapToInt(g -> g.count).sum();
        }
    }

    /**
     * A group is new or growing, or its history is unknown; frame lines never are.
     */
    static boolean growing(LogSummary.Group group) {
        if (group.template.equals(LogSummary.FRAMES_TEMPLATE)) return false;
        return group.verdict == null || group.verdict.kind() != GroupHistory.Kind.SEEN;
    }

    /**
     * Printed groups joined by a shared key or the same dependency; the sets holding a new or growing group, the
     * {@link #INCIDENTS} biggest by their new or growing lines.
     */
    static List<Incident> select(List<LogSummary.Group> printed) {
        var parent = new HashMap<LogSummary.Group, LogSummary.Group>();
        for (var group : printed) parent.put(group, group);
        var linked = new HashSet<LogSummary.Group>();
        var byDependency = new HashMap<String, LogSummary.Group>();
        for (var group : printed) {
            for (var other : group.linked)
                if (parent.containsKey(other)) {
                    union(parent, group, other);
                    linked.add(group);
                    linked.add(other);
                }
            String dependency = dependency(group);
            if (dependency != null) {
                var first = byDependency.putIfAbsent(dependency, group);
                if (first != null) union(parent, group, first);
            }
        }
        var clusters = new LinkedHashMap<LogSummary.Group, List<LogSummary.Group>>();
        for (var group : printed) clusters.computeIfAbsent(find(parent, group), k -> new ArrayList<>()).add(group);
        var incidents = new ArrayList<Incident>();
        for (var members : clusters.values()) {
            var growing = members.stream().filter(IncidentPicture::growing).toList();
            if (growing.isEmpty()) continue;
            var sorted = new ArrayList<>(members);
            sorted.sort(Comparator.<LogSummary.Group>comparingInt(g -> g.count).reversed());
            incidents.add(new Incident(List.copyOf(sorted), growing, members.stream().anyMatch(linked::contains)));
        }
        incidents.sort(Comparator.comparingInt(Incident::weight).reversed());
        return new ArrayList<>(incidents.subList(0, Math.min(INCIDENTS, incidents.size())));
    }

    private static LogSummary.Group find(Map<LogSummary.Group, LogSummary.Group> parent, LogSummary.Group group) {
        while (parent.get(group) != group) group = parent.get(group);
        return group;
    }

    private static void union(Map<LogSummary.Group, LogSummary.Group> parent, LogSummary.Group a, LogSummary.Group b) {
        parent.put(find(parent, a), find(parent, b));
    }

    /**
     * What a group's failure depends on: the subject of its dependency rule, else an address in its root cause, its
     * wrappers or its message; null when neither is known.
     */
    static String dependency(LogSummary.Group group) {
        if (group.rule != null && group.rule.category() == LogRule.Category.DEPENDENCY) return "rule " + group.rule.subject();
        String endpoint = endpoint(group);
        return endpoint == null ? null : "endpoint " + endpoint;
    }

    /**
     * {@code db.example.internal:5432}: the first address of the root message, the wrapper messages nearest to the root
     * first, then the logged message.
     */
    static String endpoint(LogSummary.Group group) {
        var texts = new ArrayList<String>();
        var signature = group.lastSignature;
        if (signature != null) {
            texts.add(signature.rootMessage());
            texts.addAll(signature.wrapperMessages().reversed());
        }
        if (group.lastView != null && group.lastView.message() != null) {
            String message = group.lastView.message();
            texts.add(message.length() > LogRules.MESSAGE_CHARS ? message.substring(0, LogRules.MESSAGE_CHARS) : message);
        }
        for (String text : texts) {
            if (text == null) continue;
            var url = URL.matcher(text);
            if (url.find()) return url.group(1);
            var address = HOST_PORT.matcher(text);
            while (address.find()) {
                String host = address.group(1);
                String suffix = host.substring(host.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                if (!FILE_SUFFIXES.contains(suffix)) return host + ":" + address.group(2);
            }
        }
        return null;
    }

    /**
     * The groups a timeline request is asked for: every group of the incidents.
     */
    static List<LogSummary.Group> timelineGroups(List<Incident> incidents) {
        var groups = new ArrayList<LogSummary.Group>();
        for (var incident : incidents) groups.addAll(incident.groups);
        return groups;
    }

    static Duration timelineStep(QueryTime.Range window) {
        return QueryService.niceStep(window.duration(), TIMELINE_STEPS);
    }

    /**
     * Onset and services from sampled lines: every line of the window ({@link Source#LINES}) or the newest part of it
     * ({@link Source#SAMPLE}, {@code window} then starting at the oldest sampled line).
     */
    static void fromLines(Incident incident, QueryTime.Range window, Source source, Function<LogEvent, String> serviceOf,
                          Function<LogEvent, String> nameOf) {
        incident.source = source;
        Instant onset = null;
        for (var group : incident.growing) {
            var times = new ArrayList<Instant>();
            for (var event : group.events) times.add(QueryTime.fromNanos(event.timestampNanos()));
            var counts = new long[times.size()];
            Arrays.fill(counts, 1);
            Instant own = changePoint(times, counts, usual(group), window);
            if (onset == null || own.isBefore(onset)) onset = own;
        }
        incident.onset = onset;
        var firsts = new HashMap<String, Instant>();
        for (var group : incident.groups)
            for (var event : group.events) {
                Instant at = QueryTime.fromNanos(event.timestampNanos());
                String service = Objects.requireNonNullElse(serviceOf.apply(event), "-");
                if (!at.isBefore(onset)) firsts.merge(service, at, (a, b) -> a.isBefore(b) ? a : b);
            }
        order(incident, firsts);
        names(incident, nameOf);
    }

    /**
     * The start-line names of the incident's sampled lines, oldest first, and the newest line of each.
     */
    private static void names(Incident incident, Function<LogEvent, String> nameOf) {
        var events = new ArrayList<LogEvent>();
        for (var group : incident.groups) events.addAll(group.events);
        events.sort(Comparator.comparingLong(LogEvent::nanos));
        for (var event : events) {
            String name = Objects.requireNonNullElse(nameOf.apply(event), "-");
            Instant at = QueryTime.fromNanos(event.timestampNanos());
            incident.named.putIfAbsent(name, at);
            incident.last.put(name, at);
        }
    }

    /**
     * Onset and services from the counts of a timeline request (evaluation time → count by service); false when the
     * counts do not cover the new or growing groups, so the sample has to do.
     */
    static boolean fromCounts(Incident incident, Map<LogSummary.Group, TreeMap<Long, Map<String, Long>>> counts, QueryTime.Range window,
                              Duration step, Function<LogEvent, String> nameOf) {
        for (var group : incident.growing) if (!counts.containsKey(group) || counts.get(group).isEmpty()) return false;
        incident.source = Source.COUNTS;
        incident.step = step;
        Instant onset = null;
        for (var group : incident.growing) {
            var times = new ArrayList<Instant>();
            var values = new ArrayList<Long>();
            for (var point : counts.get(group).entrySet()) {
                times.add(Instant.ofEpochSecond(point.getKey()).minus(step));
                values.add(point.getValue().values().stream().mapToLong(Long::longValue).sum());
            }
            Instant own = changePoint(times, values.stream().mapToLong(Long::longValue).toArray(), usual(group), window);
            if (onset == null || own.isBefore(onset)) onset = own;
        }
        incident.onset = onset.isBefore(window.start()) ? window.start() : onset;
        var firsts = new HashMap<String, Instant>();
        for (var group : incident.groups) {
            var points = counts.get(group);
            if (points != null)
                for (var point : points.entrySet()) {
                    Instant begin = Instant.ofEpochSecond(point.getKey()).minus(step);
                    if (begin.isBefore(onset)) continue;
                    for (var service : point.getValue().keySet()) {
                        String name = service.isEmpty() ? Objects.requireNonNullElse(group.lastView.service(), "-") : service;
                        firsts.merge(name, begin.isBefore(window.start()) ? window.start() : begin, (a, b) -> a.isBefore(b) ? a : b);
                    }
                }
        }
        order(incident, firsts);
        names(incident, nameOf);
        return true;
    }

    private static void order(Incident incident, Map<String, Instant> firsts) {
        var sorted = new ArrayList<>(firsts.entrySet());
        sorted.sort(Map.Entry.<String, Instant>comparingByValue().thenComparing(Map.Entry.comparingByKey()));
        for (var entry : sorted) incident.services.put(entry.getKey(), entry.getValue());
    }

    /**
     * The usual count of a group in the whole window; 0 for a new group or one whose history is unknown.
     */
    private static double usual(LogSummary.Group group) {
        return group.verdict == null || group.verdict.usual() < 0 ? 0 : group.verdict.usual();
    }

    /**
     * The point from which the lines stop fitting the usual rate: of the chronological points, the one whose lines at
     * and after it are least likely under a Poisson rate of {@code usual} lines per window (at least 0.5), scaled to
     * the part of the window left; the earliest of equals.
     */
    static Instant changePoint(List<Instant> times, long[] counts, double usual, QueryTime.Range window) {
        // Nothing is usual for a new group: it began with its first line.
        if (usual <= 0)
            for (int i = 0; i < times.size(); i++) if (counts[i] > 0) return times.get(i);
        double length = Math.max(1e-9, window.duration().toNanos());
        long[] suffix = new long[counts.length + 1];
        for (int i = counts.length - 1; i >= 0; i--) suffix[i] = suffix[i + 1] + counts[i];
        double rate = Math.max(usual, 0.5);
        Instant best = times.getFirst();
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < times.size(); i++) {
            if (counts[i] == 0 || (i > 0 && times.get(i).equals(times.get(i - 1)))) continue;
            double left = Math.min(1, Math.max(1e-9, Duration.between(times.get(i), window.end()).toNanos() / length));
            double score = surprise(suffix[i], rate * left);
            if (score > bestScore + 1e-9) {
                bestScore = score;
                best = times.get(i);
            }
        }
        return best;
    }

    /**
     * {@code -ln P(X = k)} for X ~ Poisson(λ) when k exceeds λ, else 0: how unexpected k lines are.
     */
    static double surprise(long k, double lambda) {
        if (k <= lambda) return 0;
        double logFactorial = 0;
        for (long i = 2; i <= k; i++) logFactorial += Math.log(i);
        return -(k * Math.log(lambda) - lambda - logFactorial);
    }

    /**
     * The block: a header, up to {@code keep} incidents (the biggest ones, printed oldest onset first) and a line on
     * the rest; one line when nothing is new or growing.
     */
    static List<String> render(List<Incident> incidents, int keep, List<LogSummary.Group> printed, int noiseLines, QueryTime.Range window,
                               ServiceStarts starts, ZoneId zone) {
        var lines = new ArrayList<String>();
        boolean checked = printed.stream().anyMatch(g -> g.verdict != null);
        if (incidents.isEmpty()) {
            if (checked) lines.add("Incident picture: nothing new or growing, every group below was seen before at its usual rate. "
                    + "If something is broken now, its lines may be outside this query: widen the selector or the filter.");
            return lines;
        }
        lines.add(checked ? "Incident picture: new or growing errors, oldest first (the groups below are the evidence):"
                : "Incident picture: the biggest errors, oldest first (their history was not checked, so new and usual ones are not told apart):");
        var shown = new ArrayList<>(incidents.subList(0, Math.min(keep, incidents.size())));
        shown.sort(Comparator.comparing((Incident i) -> i.onset).thenComparing(i -> -i.weight()));
        int number = 0;
        // A window over midnight prints the day with every time, or 10:24 of today would read as earlier than 17:24 of yesterday.
        boolean days = !window.start().atZone(zone).toLocalDate().equals(window.end().atZone(zone).toLocalDate());
        for (var incident : shown) lines.addAll(render(incident, ++number, window, starts, zone, days));
        var rest = new ArrayList<String>();
        int hidden = incidents.size() - shown.size();
        if (hidden > 0) rest.add(hidden + " more " + (hidden == 1 ? "incident" : "incidents") + " (output limit)");
        var inIncidents = new HashSet<LogSummary.Group>();
        for (var incident : incidents) inIncidents.addAll(incident.groups);
        var others = printed.stream().filter(g -> !inIncidents.contains(g) && g.verdict != null && g.verdict.kind() == GroupHistory.Kind.SEEN).toList();
        int otherLines = others.stream().mapToInt(g -> g.count).sum();
        if (!others.isEmpty())
            rest.add(others.size() + (others.size() == 1 ? " group" : " groups") + " seen before at the usual rate ("
                    + otherLines + (otherLines == 1 ? " line)" : " lines)"));
        var growing = printed.stream().filter(g -> !inIncidents.contains(g) && growing(g)).toList();
        if (!growing.isEmpty())
            rest.add(growing.size() + " smaller new or growing " + (growing.size() == 1 ? "group" : "groups"));
        if (noiseLines > 0) rest.add("noise " + noiseLines + (noiseLines == 1 ? " line" : " lines"));
        if (!rest.isEmpty()) lines.add("Not in the picture: " + String.join(", ", rest) + ".");
        return lines;
    }

    private static String at(Instant instant, ZoneId zone, DateTimeFormatter format, boolean days) {
        var time = instant.atZone(zone);
        return (days ? DAY.format(time) : "") + format.format(time);
    }

    private static List<String> render(Incident incident, int number, QueryTime.Range window, ServiceStarts starts, ZoneId zone, boolean days) {
        var lines = new ArrayList<String>();
        var lead = incident.lead;
        var head = new StringBuilder().append(number).append(". ").append(since(incident, window, zone, days)).append(", ").append(verdict(lead))
                .append(" — ").append(what(lead));
        var tags = new LinkedHashSet<String>();
        if (lead.rule != null) tags.add(lead.rule.tag());
        for (var group : incident.groups)
            if (group.rule != null && group.rule.category() == LogRule.Category.DEPENDENCY) tags.add(group.rule.tag());
        for (String tag : tags.stream().limit(2).toList()) head.append(' ').append(tag);
        String endpoint = null;
        for (var group : incident.groups) if (endpoint == null) endpoint = endpoint(group);
        if (endpoint != null) head.append(" endpoint ").append(endpoint);
        lines.add(head.toString());
        lines.add("   where: " + where(incident, zone, days));
        LogSummary.Group described = lead.findings.isEmpty() ? incident.groups.stream().filter(g -> !g.findings.isEmpty()).findFirst().orElse(null) : lead;
        if (described != null)
            lines.add("   " + (described == lead ? "" : "in " + described.services.iterator().next() + " ")
                    + FieldContrast.render(described.findings.getFirst(), described.services, FINDING_PARTS));
        String restarts = restarts(incident, starts, zone, days);
        if (restarts != null) lines.add("   restarts: " + restarts);
        return lines;
    }

    private static String since(Incident incident, QueryTime.Range window, ZoneId zone, boolean days) {
        String text = switch (incident.source) {
            case LINES -> "since " + at(incident.onset, zone, TIME, days);
            case COUNTS -> "since about " + at(incident.onset, zone, incident.step.toSeconds() < 60 ? SECONDS : MINUTES, days);
            case SAMPLE -> "first in the sample at " + at(incident.onset, zone, TIME, days) + " (older lines of the window were not read)";
        };
        boolean atStart = incident.source != Source.SAMPLE
                && Duration.between(window.start(), incident.onset).compareTo(window.duration().dividedBy(TIMELINE_STEPS)) <= 0;
        return atStart ? text + " (the window start: it may have begun earlier)" : text;
    }

    private static String verdict(LogSummary.Group lead) {
        var verdict = lead.verdict;
        if (verdict == null) return "history not checked";
        return switch (verdict.kind()) {
            case NEW -> "new (not seen in the " + GroupHistory.DAYS + " days before)";
            case MORE -> "more than usual (" + verdict.window() + " now, usually " + verdict.usual() + ")";
            case SEEN -> "seen before";
        };
    }

    /**
     * The root cause of the group's newest line, or the first line of its message.
     */
    private static String what(LogSummary.Group group) {
        String text;
        var signature = group.lastSignature;
        if (signature != null) {
            String message = signature.rootMessage().strip().lines().findFirst().orElse("");
            text = signature.rootType() + (message.isEmpty() ? "" : ": " + message);
        } else text = group.lastView.message().strip().lines().findFirst().orElse("");
        return truncate(text.replaceAll("\\s+", " "), HEADLINE_CHARS);
    }

    private static String where(Incident incident, ZoneId zone, boolean days) {
        var services = new ArrayList<>(incident.services.entrySet());
        var format = incident.source == Source.COUNTS ? (incident.step.toSeconds() < 60 ? SECONDS : MINUTES) : TIME;
        String prefix = incident.source == Source.COUNTS ? "about " : "";
        // Services that began at the same time (one step of the counts) share a place in the order.
        var steps = new LinkedHashMap<Instant, List<String>>();
        for (var entry : services.subList(0, Math.min(SERVICES_SHOWN, services.size())))
            steps.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        var parts = new ArrayList<String>();
        for (var step : steps.entrySet())
            parts.add((parts.isEmpty() ? "" : "then ") + String.join(" and ", step.getValue())
                    + (services.size() == 1 ? "" : " " + prefix + at(step.getKey(), zone, format, days)));
        var text = new StringBuilder(parts.isEmpty() ? "-" : String.join(", ", parts));
        if (services.size() > SERVICES_SHOWN) text.append(" (+").append(services.size() - SERVICES_SHOWN).append(" more)");
        int lines = incident.lines();
        text.append("; ").append(lines).append(incident.source == Source.LINES ? "" : " sampled").append(lines == 1 ? " line" : " lines");
        if (incident.groups.size() > 1) text.append(" in ").append(incident.groups.size()).append(" groups");
        String key = incident.linked ? incident.groups.stream().map(g -> g.linkKey).filter(Objects::nonNull).findFirst().orElse(null) : null;
        if (key != null) text.append(", one failure across services by ").append(key).append(" (followKey shows its lines in order)");
        return text.toString();
    }

    /**
     * Starts of the incident's services around it: logged while starting, a restart shortly before the onset, one
     * after it, or none in the window; null when restarts were not read.
     */
    static String restarts(Incident incident, ServiceStarts starts, ZoneId zone, boolean days) {
        if (starts == null) return null;
        if (!starts.checked()) return "not checked (the query for start lines failed)";
        var phrases = new ArrayList<String>();
        // Lines a service logs while starting come with its starts; its later restarts are then no news.
        var starting = incident.groups.stream().filter(g -> g.whileStarting > 0 && g.start != null)
                .max(Comparator.comparingInt(g -> g.whileStarting)).orElse(null);
        if (starting != null) {
            var start = starting.start;
            phrases.add(starting.whileStarting + " of " + starting.count + (starting.count == 1 ? " line" : " lines") + " logged while "
                    + start.service() + " was starting (newest start " + at(start.begin(), zone, TIME, days) + "–"
                    + (start.end() == null ? "did not finish" : TIME.format(start.end().atZone(zone))) + ")" + deploy(starts, start));
        }
        for (String service : incident.named.keySet()) {
            boolean after = starting != null && starting.start.service().equals(service);
            for (var start : starts.startsOf(service)) {
                Instant at = start.end() == null ? start.begin() : start.end();
                String name = start.service();
                if (!at.isAfter(incident.onset) && !at.isBefore(incident.onset.minus(BEFORE)))
                    phrases.add(name + " restarted at " + at(at, zone, TIME, days) + ", just before" + deploy(starts, start));
                else if (!after && start.begin().isAfter(incident.onset)) {
                    // The first restart after the onset only: did the lines stop with it.
                    after = true;
                    Instant last = incident.last.get(service);
                    phrases.add(name + (start.end() == null ? " began a start at " + at(start.begin(), zone, TIME, days) + " that did not finish"
                            : " restarted at " + at(at, zone, TIME, days)) + " after it began" + deploy(starts, start)
                            + (last != null && last.isBefore(start.begin()) ? ", none of these lines after it" : ", the lines went on"));
                }
            }
        }
        if (phrases.isEmpty())
            return "none of " + String.join(", ", incident.services.keySet()) + " restarted in the window";
        var distinct = new ArrayList<>(new LinkedHashSet<>(phrases));
        return String.join("; ", distinct.subList(0, Math.min(RESTART_PHRASES, distinct.size())))
                + (distinct.size() > RESTART_PHRASES ? " (+" + (distinct.size() - RESTART_PHRASES) + " more)" : "");
    }

    private static String deploy(ServiceStarts starts, ServiceStarts.Start start) {
        var deploy = starts.deployOf(start);
        return deploy == null ? "" : " (deploy: " + deploy.after().changeFrom(deploy.before()) + ")";
    }
}
