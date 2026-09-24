package ru.it_spectrum.ai.loki.mcp.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.client.LokiResponses;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;
import ru.it_spectrum.ai.loki.mcp.model.LogEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static ru.it_spectrum.ai.loki.mcp.service.LogText.*;

/**
 * Log pages, counts and metrics rendered as text. Each call is one bounded Loki request (two for time buckets).
 */
@Service
public class QueryService {
    public static final int DEFAULT_LIMIT = 50;
    public static final int DEFAULT_CONTEXT = 20;
    public static final int TIME_BUCKETS = 12;
    public static final int METRIC_STEPS = 20;
    public static final int DEFAULT_FOLLOW = 100;
    static final int KNOWN_CAUSES = 10;
    static final int GONE_GROUPS = 5;
    static final int FIRST_PAGE = 50;
    private static final List<Duration> NICE_STEPS = List.of(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(10),
            Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(10),
            Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(2), Duration.ofHours(3),
            Duration.ofHours(6), Duration.ofHours(12), Duration.ofDays(1));
    private static final java.util.regex.Pattern KEY_PAIR = java.util.regex.Pattern.compile("\\s*([A-Za-z][\\w.]{0,63})\\s*[=:]\\s*(.+?)\\s*");
    private final ConnectionRegistry registry;
    private final LokiHttpClient client;
    private final Clock clock;
    private final java.util.concurrent.ConcurrentHashMap<String, EventNormalizer> normalizers = new java.util.concurrent.ConcurrentHashMap<>();
    private final SelectorCheck check;

    @Autowired
    public QueryService(ConnectionRegistry registry, LokiHttpClient client) {
        this(registry, client, Clock.systemUTC());
    }

    public QueryService(ConnectionRegistry registry, LokiHttpClient client, Clock clock) {
        this.registry = registry;
        this.client = client;
        this.clock = clock;
        this.check = new SelectorCheck(client);
    }

    private static String footer(List<LogEvent> events, List<String> marked, int dropped, boolean more, int limit, ZoneId zone) {
        if (events.isEmpty()) return "No lines match in this window. Try a wider window (e.g. start=\"now-6h\"), "
                + "check labels and fields with discoverLogs, or simplify the filter.";
        int markers = (int) marked.subList(0, Math.min(dropped, marked.size())).stream().filter(l -> l.startsWith("--- ")).count();
        int shown = events.size() - (dropped - markers);
        if (shown < 1) shown = 1;
        var oldest = QueryTime.fromNanos(events.get(events.size() - shown).timestampNanos());
        var text = new StringBuilder();
        if (dropped > 0)
            text.append("Output limit reached: showing ").append(shown).append(" newest of ").append(events.size()).append(" fetched lines. ");
        if (more || dropped > 0) {
            text.append("Shown ").append(shown).append(" newest lines; oldest shown ").append(iso(oldest, zone)).append(". ");
            text.append("Older: repeat with end=\"").append(iso(QueryTime.ceilMillis(oldest), zone)).append("\". ");
            text.append("Too many lines? Narrow the query (add a filter or level) or use countLogs / summarizeLogs.");
        } else text.append("Shown all ").append(shown).append(" matching lines.");
        return text.toString();
    }

    /**
     * {@code   dependency     SMEV  3 lines}: matched rules other than noise, summed by category and subject.
     */
    private static List<String> knownCauses(List<LogSummary.Group> groups) {
        var lines = new LinkedHashMap<String, Integer>();
        for (var group : groups) {
            if (group.rule == null) continue;
            String key = String.format("  %-13s  %s", group.rule.category().text(),
                    group.rule.subject() == null ? group.rule.rule().id() : group.rule.subject());
            lines.merge(key, group.count, Integer::sum);
        }
        var sorted = new ArrayList<>(lines.entrySet());
        sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        var result = new ArrayList<String>();
        for (var entry : sorted.subList(0, Math.min(KNOWN_CAUSES, sorted.size())))
            result.add(entry.getKey() + "  " + entry.getValue() + (entry.getValue() == 1 ? " line" : " lines"));
        if (sorted.size() > KNOWN_CAUSES) result.add("  (+" + (sorted.size() - KNOWN_CAUSES) + " more)");
        return result;
    }

    /**
     * Filters of the noise rules that hold at least a tenth of the sample, biggest first: a short query to retry with.
     */
    private static String noiseFilters(List<LogSummary.Group> noise, int sampled) {
        var lines = new LinkedHashMap<String, Integer>();
        for (var group : noise)
            if (group.rule.rule().filter() != null)
                lines.merge(group.rule.rule().filter().strip(), group.count, Integer::sum);
        var filters = new ArrayList<>(lines.entrySet());
        filters.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        var result = new ArrayList<String>();
        for (var filter : filters) if (filter.getValue() * 10 >= sampled) result.add(filter.getKey());
        return String.join(" ", result);
    }

    private static String contextFooter(List<LogEvent> earlier, List<LogEvent> later, int targets, int wantBefore, int wantAfter,
                                        int keepBefore, int keepAfter, int dropped, QueryTime.Point point, Duration reach, ZoneId zone) {
        var text = new StringBuilder();
        int beforeCount = earlier.size() - targets;
        if (dropped > 0)
            text.append("Output limit reached: showing ").append(keepBefore).append(" before and ").append(keepAfter)
                    .append(" after of ").append(earlier.size() + later.size()).append(" fetched lines. ");
        String span = QueryTime.human(reach);
        if (targets > 0 && targets == earlier.size() && targets > wantBefore)
            text.append("All ").append(targets).append(" fetched lines are at this time; ")
                    .append(point.precision().compareTo(Duration.ofMillis(1)) > 0 ? "pass the time with milliseconds as printed by queryLogs"
                            : "repeat with a larger before (e.g. before=" + Math.min(wantBefore * 5 + 5, 200) + ")")
                    .append(" to see what came before. ");
        else if (beforeCount < wantBefore)
            text.append("No earlier lines within ").append(span).append(" before this time. ");
        else if (keepBefore > 0)
            text.append("Earlier: repeat with time=\"").append(iso(QueryTime.fromNanos(earlier.get(beforeCount - keepBefore).timestampNanos()), zone)).append("\", after=0. ");
        if (wantAfter > 0 && later.size() < wantAfter)
            text.append("No later lines within ").append(span).append(" after this time. ");
        else if (keepAfter > 0)
            text.append("Later: repeat with time=\"").append(iso(QueryTime.fromNanos(later.get(keepAfter - 1).timestampNanos()), zone)).append("\", before=0. ");
        text.append("Full original line: queryLogs with raw=true and a narrow filter.");
        return text.toString();
    }

    private static String megabytes(long bytes) {
        return BigDecimal.valueOf(bytes).divide(BigDecimal.valueOf(1024 * 1024), 1, RoundingMode.HALF_UP).toPlainString();
    }

    static String countExpression(String query, Duration range, String by) {
        String inner = "count_over_time(" + query.strip() + " [" + QueryTime.lokiDuration(range) + "])";
        return by == null ? "sum(" + inner + ")" : "sum by (" + by + ") (" + inner + ")";
    }

    static Duration niceStep(Duration window) {
        return niceStep(window, METRIC_STEPS);
    }

    static Duration niceStep(Duration window, int points) {
        Duration minimum = window.dividedBy(points);
        for (var candidate : NICE_STEPS) if (candidate.compareTo(minimum) >= 0) return candidate;
        return NICE_STEPS.getLast();
    }

    private static void requireLogQuery(String query) {
        if (query == null || query.isBlank())
            throw Errors.invalid("query is required, e.g. {app=\"backend\"} |= \"ERROR\", or pass service/level/text instead, "
                    + "e.g. service=\"backend\", level=\"error\".");
        if (!query.strip().startsWith("{"))
            throw Errors.invalid("A log query starts with a stream selector in braces, e.g. {app=\"backend\"} |= \"ERROR\". "
                    + "For metric expressions use queryMetrics.");
    }

    private static List<LokiResponses.VectorSample> vector(LokiResponses.QueryResponse response) {
        if (!(response.data() instanceof LokiResponses.Vector vector)) throw notMetric();
        return vector.samples();
    }

    private static long sum(List<LokiResponses.VectorSample> samples) {
        long total = 0;
        for (var sample : samples) total += value(sample.sample().value());
        return total;
    }

    private static long value(String metricValue) {
        try {
            return new BigDecimal(metricValue).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (NumberFormatException | ArithmeticException ignored) {
            return 0;
        }
    }

    private static BigDecimal stepSeconds(Duration step) {
        return BigDecimal.valueOf(step.toMillis(), 3);
    }

    private static LokiOperationException notMetric() {
        return Errors.invalid("Loki returned a result type this tool cannot show. Check that the expression is what the tool expects.");
    }

    /**
     * The normalizer of a connection, which knows the plain-text line formats of its rules catalogue.
     */
    private EventNormalizer normalizer(ru.it_spectrum.ai.loki.mcp.connection.ConnectionDefinition definition) {
        return normalizers.computeIfAbsent(definition.name(), name -> new EventNormalizer(definition.formats()));
    }

    /**
     * {@code text} with the reason of an empty result on a line of its own, when one is found.
     */
    private String why(String text, String connection, String query, QueryTime.Range window) {
        String why = check.explain(connection, query, window);
        return why == null ? text : text + "\n" + why;
    }

    /**
     * Newest {@code limit} lines of the window, printed in chronological order.
     */
    public String logs(String connection, String query, String start, String end, Integer limit, Boolean raw) {
        return logs(connection, query, null, null, null, start, end, limit, raw);
    }

    /**
     * {@code service}, {@code level} and {@code text} build the query instead of {@code query} ({@link QueryIntent}).
     */
    public String logs(String connection, String query, String service, String level, String textFilter, String start, String end,
                       Integer limit, Boolean raw) {
        var definition = registry.require(connection);
        var window = QueryTime.range(start, end, clock.instant(), definition.timezone(), definition.limits().maxIntervalSeconds());
        query = QueryIntent.resolve(definition, client, query, service, level, textFilter, window);
        requireLogQuery(query);
        int usedLimit = limit == null ? Math.min(DEFAULT_LIMIT, definition.limits().maxEntries()) : limit;
        if (usedLimit <= 0 || usedLimit > definition.limits().maxEntries())
            throw Errors.invalid("limit must be between 1 and " + definition.limits().maxEntries() + " for this connection.");
        var events = fetch(connection, query, window, usedLimit, LokiHttpClient.Direction.BACKWARD);
        boolean more = events.size() >= usedLimit;
        ZoneId zone = definition.timezone();
        var lines = new ArrayList<String>(events.size());
        for (var event : events)
            lines.add(line(event, normalizer(definition).view(event, definition.serviceLabels()), zone, Boolean.TRUE.equals(raw)));
        String header = query.strip() + " — " + connection + ", " + window(window, zone) + ", "
                + (events.isEmpty() ? "no matching lines." : more ? "newest " + events.size() + " of more:" : "all " + events.size() + " lines:");
        var marked = withDateMarkers(events, lines, zone);
        // Markers are not events; keep the two lists aligned when the oldest lines are dropped for the budget.
        String text = fit(header, marked, dropped -> footer(events, marked, dropped, more, usedLimit, zone),
                definition.limits().maxResponseBytes() - ENVELOPE_BYTES);
        return events.isEmpty() ? why(text, connection, query, window) : text;
    }

    /**
     * Groups of repeated messages in the newest {@code sample} lines: count, first/last time and the newest example.
     * The numbers describe the sample, never the window; the footer says so and names countLogs for the total.
     */
    public String summarize(String connection, String query, String start, String end, Integer sample) {
        return summarize(connection, query, null, null, null, start, end, sample);
    }

    public String summarize(String connection, String query, String service, String level, String textFilter, String start, String end,
                            Integer sample) {
        var definition = registry.require(connection);
        var window = QueryTime.range(start, end, clock.instant(), definition.timezone(), definition.limits().maxIntervalSeconds());
        query = QueryIntent.resolve(definition, client, query, service, level, textFilter, window);
        requireLogQuery(query);
        int maximum = definition.limits().maxEntries();
        int usedSample = sample == null ? Math.min(LogSummary.DEFAULT_SAMPLE, maximum) : sample;
        if (usedSample <= 0 || usedSample > maximum)
            throw Errors.invalid("sample must be between 1 and " + maximum + " for this connection.");
        var sampled = sample(connection, query, window, usedSample, definition.limits().maxHttpResponseBytes());
        var events = sampled.events();
        ZoneId zone = definition.timezone();
        String where = query.strip() + " — " + connection + ", " + window(window, zone);
        if (events.isEmpty())
            return why("Summary of " + where + ": no matching lines.\nNo lines match in this window. Try a wider window "
                    + "(e.g. start=\"now-6h\"), check labels and fields with discoverLogs, or simplify the filter.", connection, query, window);
        boolean more = events.size() >= usedSample || sampled.cutByBytes();
        var starts = starts(connection, query, window);
        if (starts != null) starts.count(events);
        var groups = LogSummary.group(events, normalizer(definition), definition.serviceLabels(), definition.applicationPackages(), definition.rules(),
                starts == null ? event -> null : starts::startOf);
        String span = TIME.format(QueryTime.fromNanos(events.getFirst().timestampNanos()).atZone(zone)) + "–"
                + TIME.format(QueryTime.fromNanos(events.getLast().timestampNanos()).atZone(zone));
        String header = "Summary of " + where + ": " + (more ? "newest " + events.size() + " lines sampled (more exist"
                                                               + (sampled.cutByBytes() ? "; stopped at " + megabytes(sampled.bytes()) + " MB of log text" : "") + "), "
                : "all " + events.size() + " lines, ") + "spanning " + span + ", " + groups.size()
                + (groups.size() == 1 ? " distinct message." : " distinct messages.");
        // Noise groups (by the connection's rules) are listed apart and never take a place in the top or rare lists.
        var noise = new ArrayList<LogSummary.Group>();
        var listed = new ArrayList<LogSummary.Group>();
        for (var group : groups) (LogSummary.isNoise(group) ? noise : listed).add(group);
        var top = listed.subList(0, Math.min(LogSummary.TOP_GROUPS, listed.size()));
        var rare = new ArrayList<LogSummary.Group>();
        for (var group : listed.subList(top.size(), listed.size()))
            if (group.count <= LogSummary.RARE_LINES) rare.add(group);
        rare.sort(Comparator.comparing((LogSummary.Group g) -> g.last.nanos()).reversed());
        var known = knownCauses(listed);
        int noiseLines = noise.stream().mapToInt(g -> g.count).sum();
        var printed = new ArrayList<LogSummary.Group>(top);
        printed.addAll(rare.subList(0, Math.min(LogSummary.RARE_GROUPS, rare.size())));
        var keys = new HashSet<String>();
        for (var group : groups) keys.add(group.template);
        var past = past(connection, query, window, printed, keys, !more, events);
        int budget = definition.limits().maxResponseBytes() - ENVELOPE_BYTES;
        int keepTop = top.size(), keepRare = Math.min(LogSummary.RARE_GROUPS, rare.size()),
                keepNoise = Math.min(LogSummary.NOISE_GROUPS, noise.size());
        int keepStarts = starts == null || starts.isEmpty() ? 0 : Math.min(ServiceStarts.SERVICES_SHOWN, starts.services());
        int keepGone = past.gone() == null ? 0 : past.gone().size();
        int keepIncidents = past.incidents().size();
        int fullRare = keepRare, fullNoise = keepNoise;
        while (true) {
            var lines = new ArrayList<String>();
            if (past.header() != null) lines.add(past.header());
            lines.addAll(IncidentPicture.render(past.incidents(), keepIncidents, printed, noiseLines, window, starts, zone));
            if (!known.isEmpty()) {
                lines.add("Known causes by the rules of this connection (lines in the sample):");
                lines.addAll(known);
            }
            if (starts != null && !starts.isEmpty()) lines.addAll(starts.render(keepStarts, zone));
            if (keepTop > 0) {
                lines.add("Groups by count in the sample (first–last time, level, service, newest example):");
                for (var group : top.subList(0, keepTop)) lines.addAll(LogSummary.render(group, zone));
            }
            int hiddenGroups = listed.size() - keepTop, hiddenLines = 0;
            for (var group : listed.subList(keepTop, listed.size())) hiddenLines += group.count;
            if (keepRare > 0) {
                lines.add("Rare (" + (LogSummary.RARE_LINES == 1 ? "1 line" : "1–" + LogSummary.RARE_LINES + " lines") + " each, easy to miss):");
                for (var group : rare.subList(0, keepRare)) lines.addAll(LogSummary.render(group, zone));
                hiddenGroups -= keepRare;
                for (var group : rare.subList(0, keepRare)) hiddenLines -= group.count;
            }
            if (hiddenGroups > 0)
                lines.add("  (+" + hiddenGroups + " more groups, " + hiddenLines + " lines: narrow the query to see them)");
            if (past.goneTitle() != null && (past.gone() == null || keepGone > 0)) {
                lines.add(past.goneTitle());
                if (past.gone() != null)
                    for (var group : past.gone().subList(0, keepGone)) lines.add(LogSummary.renderGone(group));
            }
            if (!noise.isEmpty()) {
                lines.add("Noise by the rules of this connection (" + noiseLines + " of " + events.size() + " sampled lines, not listed above):");
                for (var group : noise.subList(0, keepNoise)) lines.add(LogSummary.renderNoise(group, zone));
                int hiddenNoise = noise.size() - keepNoise, hiddenNoiseLines = 0;
                for (var group : noise.subList(keepNoise, noise.size())) hiddenNoiseLines += group.count;
                if (hiddenNoise > 0)
                    lines.add("  (+" + hiddenNoise + " more noise groups, " + hiddenNoiseLines + " lines)");
                String filters = noiseFilters(noise, events.size());
                // Noise that crowds a cut sample hides the rest of the window: offer the filters that drop it.
                if (more && noiseLines * 3 >= events.size() && !filters.isEmpty())
                    lines.add("Noise takes " + noiseLines + " of " + events.size() + " sampled lines; to sample past it, add " + filters
                            + " to the query.");
            }
            var footer = new StringBuilder();
            if (keepTop < top.size() || keepRare < fullRare || keepNoise < fullNoise)
                footer.append("Output limit reached: showing ").append(keepTop + keepRare + keepNoise).append(" of ").append(groups.size()).append(" groups. ");
            footer.append("Counts are for the ").append(events.size()).append(" sampled lines only; countLogs gives the number for the whole window. ")
                    .append("To read one group: queryLogs with |= \"<distinctive part of its message>\".");
            if (top.subList(0, keepTop).stream().anyMatch(g -> !g.links.isEmpty()) || rare.subList(0, keepRare).stream().anyMatch(g -> !g.links.isEmpty()))
                footer.append(" Groups with the same 'linked:' key are one failure seen by several services; followKey with that key shows its lines in order.");
            String text = assemble(header, lines, footer.toString());
            if (bytes(text) <= budget) return text;
            // Rare groups go first, then the groups gone, noise, restarted services, the top list from its end and last
            // the smallest incidents; one group and one incident always stay.
            if (keepRare > 0) keepRare--;
            else if (keepGone > 0) keepGone--;
            else if (keepNoise > (keepTop == 0 ? 1 : 0)) keepNoise--;
            else if (keepStarts > 0) keepStarts--;
            else if (keepTop > 1) keepTop--;
            else if (keepIncidents > 1) keepIncidents--;
            else throw Errors.failure(ErrorCode.RESPONSE_BUDGET_EXCEEDED,
                        "Even a minimal response does not fit maxResponseBytes of this connection. Narrow the query or raise the limit.");
        }
    }

    /**
     * History of the printed groups ({@link GroupHistory}: two metric requests per batch of groups), the onset of the
     * incidents ({@link IncidentPicture}), the background of {@link FieldContrast} and the "not now" page of a day
     * earlier, in this order and one request after another:
     * parallel requests of one call trip a stand's rate limit and leave its queue full for the next call. No request
     * starts after the deadline; a failure costs its own lines, never the summary. Sets {@code history} and
     * {@code fields} of the printed groups.
     */
    private Past past(String connection, String query, QueryTime.Range window, List<LogSummary.Group> printed, Set<String> keys,
                      boolean wholeWindow, List<LogEvent> sample) {
        var definition = registry.require(connection);
        long deadline = System.nanoTime() + GroupHistory.DEADLINE.toNanos();
        var batches = GroupHistory.batches(query, printed);
        var previous = new HashMap<LogSummary.Group, long[]>();
        var same = new HashMap<LogSummary.Group, long[]>();
        for (var batch : batches)
            if (System.nanoTime() < deadline)
                previous.putAll(counts(connection, batch, GroupHistory.previousDays(batch, window)));
        boolean longWindow = window.duration().compareTo(GroupHistory.SAME_HOURS) > 0;
        for (var batch : batches) {
            // Groups never seen before need no comparison with the same hours.
            boolean seen = batch.fragments().keySet().stream().anyMatch(g -> previous.containsKey(g) && Arrays.stream(previous.get(g)).sum() > 0);
            if (!seen) continue;
            // A long window read whole needs no count of itself: the sample holds every line of each group.
            if (longWindow && wholeWindow)
                for (var group : batch.fragments().keySet()) same.put(group, new long[]{group.count, 0});
            else if (System.nanoTime() < deadline)
                same.putAll(counts(connection, batch, GroupHistory.sameHours(batch, window)));
        }
        var verdicts = new ArrayList<GroupHistory.Verdict>();
        int notChecked = 0;
        for (var batch : batches)
            for (var group : batch.fragments().keySet()) {
                if (!previous.containsKey(group)) {
                    group.history = GroupHistory.NOT_CHECKED;
                    notChecked++;
                    continue;
                }
                var verdict = GroupHistory.classify(previous.get(group), same.get(group), window.duration());
                group.history = verdict.text();
                group.verdict = verdict;
                verdicts.add(verdict);
            }
        String header = batches.isEmpty() ? null : GroupHistory.header(verdicts, notChecked);
        var incidents = IncidentPicture.select(printed);
        onsets(connection, query, window, incidents, wholeWindow, sample, deadline);
        contrast(connection, query, window, printed, sample, deadline);
        // The groups of a day earlier can only be "gone" when the sample of this window saw every line of it.
        if (!wholeWindow || window.duration().getSeconds() > GroupHistory.DAY_SECONDS)
            return new Past(header, null, null, incidents);
        String title = "Seen at these hours a day earlier, not now";
        var dayBefore = new QueryTime.Range(window.start().minus(Duration.ofDays(1)), window.end().minus(Duration.ofDays(1)));
        Sample sampled;
        try {
            if (System.nanoTime() >= deadline) throw Errors.failure(ErrorCode.UPSTREAM_TIMEOUT, "deadline");
            sampled = sample(connection, query, dayBefore, FIRST_PAGE, definition.limits().maxHttpResponseBytes());
        } catch (LokiOperationException e) {
            if (e.error().code() == ErrorCode.OPERATION_CANCELLED) throw e;
            return new Past(header, title + ": not checked (Loki did not answer in time or failed).", null, incidents);
        }
        var gone = new ArrayList<LogSummary.Group>();
        for (var group : LogSummary.group(sampled.events(), normalizer(definition), definition.serviceLabels(), definition.applicationPackages(), definition.rules()))
            if (!LogSummary.isNoise(group) && !keys.contains(group.template) && gone.size() < GONE_GROUPS)
                gone.add(group);
        if (gone.isEmpty()) return new Past(header, null, null, incidents);
        return new Past(header, title + " (in a sample of " + sampled.events().size() + " lines of " + window(dayBefore, definition.timezone()) + "):",
                gone, incidents);
    }

    /**
     * Chronological page of at most {@code limit} entries: the newest ones for BACKWARD, the oldest ones for FORWARD.
     */

    /**
     * When each incident began and in which order its services joined: from the sampled lines when they are every
     * line of the window, else from counts of its groups in steps of the window (one request per batch, the same
     * fragments as the history), else from the sample alone.
     */
    private void onsets(String connection, String query, QueryTime.Range window, List<IncidentPicture.Incident> incidents, boolean wholeWindow,
                        List<LogEvent> sample, long deadline) {
        if (incidents.isEmpty()) return;
        var definition = registry.require(connection);
        java.util.function.Function<LogEvent, String> serviceOf = event -> normalizer(definition).view(event, definition.serviceLabels()).service();
        java.util.function.Function<LogEvent, String> nameOf = event -> {
            var values = new LinkedHashMap<String, String>();
            normalizer(definition).parse(event.line(), values, new LinkedHashMap<>());
            return ServiceStarts.service(event, values, definition.serviceLabels());
        };
        if (wholeWindow) {
            for (var incident : incidents)
                IncidentPicture.fromLines(incident, window, IncidentPicture.Source.LINES, serviceOf, nameOf);
            return;
        }
        Duration step = IncidentPicture.timelineStep(window);
        var counts = new HashMap<LogSummary.Group, TreeMap<Long, Map<String, Long>>>();
        for (var batch : GroupHistory.batches(query, IncidentPicture.timelineGroups(incidents))) {
            if (System.nanoTime() >= deadline) break;
            var request = GroupHistory.timeline(batch, window, step);
            try {
                var response = client.queryRange(connection, request.expression(), request.start(), request.end(), definition.limits().maxEntries(),
                        LokiHttpClient.Direction.FORWARD, stepSeconds(step), request.logged());
                counts.putAll(GroupHistory.timeline(batch, response));
            } catch (LokiOperationException e) {
                if (e.error().code() == ErrorCode.OPERATION_CANCELLED) throw e;
                break;
            } catch (IllegalStateException e) {
                break;
            }
        }
        var sampled = new QueryTime.Range(QueryTime.fromNanos(sample.getFirst().timestampNanos()), window.end());
        for (var incident : incidents)
            if (!IncidentPicture.fromCounts(incident, counts, window, step, nameOf))
                IncidentPicture.fromLines(incident, sampled, IncidentPicture.Source.SAMPLE, serviceOf, nameOf);
    }

    /**
     * The background of the window for {@link FieldContrast}: slices over the query's stream selector without its
     * pipeline and level matchers, narrowed to the services of the groups to explain, spread over the window first so
     * that a deadline leaves an even sample; a failed slice ends the reading. Sets {@code fields} of the groups.
     */
    private void contrast(String connection, String query, QueryTime.Range window, List<LogSummary.Group> groups, List<LogEvent> sample,
                          long deadline) {
        var definition = registry.require(connection);
        var targets = groups.stream().filter(g -> g.events.size() >= FieldContrast.MIN_LINES).toList();
        String selector = ServiceStarts.selector(query);
        if (targets.isEmpty() || selector == null) return;
        selector = GroupHistory.narrow(selector, targets);
        int limit = Math.min(FieldContrast.SLICE_LINES, definition.limits().maxEntries());
        var slices = FieldContrast.slices(window);
        var background = new ArrayList<FieldContrast.Line>();
        for (int i = 0; i < slices.size(); i++) {
            if (System.nanoTime() >= deadline) break;
            // 7 is coprime with 12: 0, 7, 2, 9, 4, … visits every slice, far apart first.
            var slice = slices.get(i * 7 % slices.size());
            List<LogEvent> events;
            try {
                events = fetch(connection, selector, slice, limit, LokiHttpClient.Direction.BACKWARD);
            } catch (LokiOperationException e) {
                if (e.error().code() == ErrorCode.OPERATION_CANCELLED) throw e;
                break;
            }
            for (var event : events) {
                var view = normalizer(definition).view(event, definition.serviceLabels());
                if (view.service() != null && FieldContrast.background(view))
                    background.add(new FieldContrast.Line(view.service(), FieldContrast.fields(event, normalizer(definition))));
            }
        }
        var sampleValues = new HashMap<String, Map<String, Set<String>>>();
        for (var event : sample) {
            String service = normalizer(definition).view(event, definition.serviceLabels()).service();
            if (service == null) continue;
            for (var field : FieldContrast.fields(event, normalizer(definition)).entrySet())
                sampleValues.computeIfAbsent(service, k -> new HashMap<>()).computeIfAbsent(field.getKey(), k -> new HashSet<>()).add(field.getValue());
        }
        for (var group : targets) {
            group.findings = FieldContrast.analyse(group, normalizer(definition), background, sampleValues);
            group.fields = group.findings.stream().map(f -> "         " + FieldContrast.render(f, group.services, FieldContrast.PARTS)).toList();
        }
    }

    /**
     * Counts of one history request by group; empty when Loki failed or answered something else, so the groups say
     * they were not checked. The server's log shows the request without the fragments.
     */
    private Map<LogSummary.Group, long[]> counts(String connection, GroupHistory.Batch batch, GroupHistory.Request request) {
        try {
            var response = client.queryRange(connection, request.expression(), request.start(), request.end(),
                    registry.require(connection).limits().maxEntries(), LokiHttpClient.Direction.FORWARD, GroupHistory.step(), request.logged());
            return GroupHistory.counts(batch, request, response);
        } catch (LokiOperationException e) {
            if (e.error().code() == ErrorCode.OPERATION_CANCELLED) throw e;
            return Map.of();
        } catch (IllegalStateException e) {
            return Map.of();
        }
    }

    /**
     * Spring Boot start and graceful stop lines of the query's streams in the window: one more Loki request, whose
     * failure costs the block, never the summary. Null when the query has no stream selector to reuse.
     */
    private ServiceStarts starts(String connection, String query, QueryTime.Range window) {
        var definition = registry.require(connection);
        String selector = ServiceStarts.selector(query);
        if (selector == null) return null;
        int limit = definition.limits().maxEntries();
        try {
            var events = fetch(connection, selector + ServiceStarts.FILTER, window, limit, LokiHttpClient.Direction.BACKWARD);
            return ServiceStarts.of(selector, events, events.size() >= limit, normalizer(definition), definition.serviceLabels(), window.end());
        } catch (LokiOperationException e) {
            if (e.error().code() == ErrorCode.OPERATION_CANCELLED) throw e;
            return ServiceStarts.failed(selector, e.error().code());
        }
    }

    /**
     * Every line of a stream selector that holds one identifier, oldest first, across services: what happened to one
     * task, request or message on a stand without tracing. The key is filtered in Loki by its value and then matched
     * as a whole token here; errors show their root cause and the rule that knows them.
     */
    public String followKey(String connection, String selector, String key, String start, String end, Integer limit) {
        var definition = registry.require(connection);
        if (selector == null || selector.isBlank())
            throw Errors.invalid("selector is required: the stream selector of every service to search, e.g. {namespace=\"dev\"}.");
        if (selector.length() > DiscoveryLimits.SELECTOR_CHARACTERS || !DiscoveryService.SELECTOR.matcher(selector).matches())
            throw Errors.invalid("selector must be a stream selector only, like {namespace=\"dev\"}: followKey adds the key filter itself. "
                    + "Take it from discoverLogs or from the braces of the query you used.");
        if (key == null || key.isBlank())
            throw Errors.invalid("key is required: an identifier as printed by summarizeLogs, e.g. \"taskExecutionId=13548\", or a bare value like \"ERR-5ced1eb2\".");
        var pair = KEY_PAIR.matcher(key);
        String value = pair.matches() ? pair.group(2) : key.strip();
        if (value.length() < 3 || value.length() > 200 || value.chars().anyMatch(c -> c == '"' || c == '\\' || c == '`' || Character.isISOControl(c)))
            throw Errors.invalid("key value must be 3 to 200 characters without quotes or backslashes, e.g. \"taskExecutionId=13548\".");
        var window = QueryTime.range(start, end, clock.instant(), definition.timezone(), definition.limits().maxIntervalSeconds());
        int maximum = definition.limits().maxEntries();
        int usedLimit = limit == null ? Math.min(DEFAULT_FOLLOW, maximum) : limit;
        if (usedLimit <= 0 || usedLimit > maximum)
            throw Errors.invalid("limit must be between 1 and " + maximum + " for this connection.");
        String scope = selector.strip();
        var fetched = fetch(connection, scope + " |= \"" + value + "\"", window, usedLimit, LokiHttpClient.Direction.FORWARD);
        boolean more = fetched.size() >= usedLimit;
        var token = CorrelationKeys.token(value);
        var events = new ArrayList<LogEvent>();
        for (var event : fetched) if (token.matcher(event.line()).find()) events.add(event);
        int partial = fetched.size() - events.size();
        ZoneId zone = definition.timezone();
        String what = pair.matches() ? pair.group(1) + "=" + value : value;
        String where = "Lines with " + what + " in " + scope + " — " + connection + ", " + window(window, zone);
        String partialNote = partial == 0 ? "" : " " + partial + (partial == 1 ? " line" : " lines")
                                                 + " holding " + value + " only inside a longer word were left out.";
        if (events.isEmpty())
            return why(where + ": no lines." + partialNote + "\nNo line of the window holds this value. Widen the window "
                            + "around the time the key was seen (start=\"...\", end=\"...\"), or use a selector that covers every service.",
                    connection, scope + " |= \"" + value + "\"", window);
        var lines = new ArrayList<String>();
        var services = new LinkedHashSet<String>();
        String firstError = null;
        var advised = new HashSet<String>();
        for (var event : events) {
            var view = normalizer(definition).view(event, definition.serviceLabels());
            var signature = LogSummary.signature(view, definition.applicationPackages());
            var rule = LogRules.match(definition.rules(), view, signature);
            String service = view.service() == null ? "-" : view.service();
            services.add(service);
            boolean error = "ERROR".equals(view.level()) || "FATAL".equals(view.level()) || signature != null;
            String time = TIME.format(QueryTime.fromNanos(event.timestampNanos()).atZone(zone));
            if (error && firstError == null) firstError = time + " " + service;
            var text = new StringBuilder(time).append(' ').append(String.format("%-5s", view.level() == null ? "-" : view.level()))
                    .append(' ').append(service).append("  ")
                    .append(truncate(view.message().replace('\n', ' ').replace("\r", "").strip(), MESSAGE_CHARS));
            // The advice of a rule is printed at its first line; later lines of the same rule carry its tag only.
            boolean advice = rule != null && advised.add(rule.rule().id() + "\n" + rule.advice());
            for (String cause : LogSummary.causeLines(view, signature, rule, advice)) text.append('\n').append(cause);
            lines.add(text.toString());
        }
        String header = where + ": " + (more ? "oldest " : "all ") + events.size() + (events.size() == 1 ? " line" : " lines")
                + " in " + services.size() + (services.size() == 1 ? " service (" : " services (") + String.join(", ", services) + ")"
                + (firstError == null ? ", no errors." : "; first error " + firstError + ".") + partialNote;
        var marked = withDateMarkers(events, lines, zone);
        int budget = definition.limits().maxResponseBytes() - ENVELOPE_BYTES;
        // Oldest first: the story of a key starts at its first line, so a cut drops the newest lines.
        for (int keep = marked.size(); keep >= 1; keep--) {
            var shown = marked.subList(0, keep);
            int shownEvents = (int) shown.stream().filter(l -> !l.startsWith("--- ")).count();
            var footer = new StringBuilder();
            if (keep < marked.size())
                footer.append("Output limit reached: showing the oldest ").append(shownEvents).append(" of ").append(events.size()).append(" lines. ");
            if (keep < marked.size() || more) {
                var newest = QueryTime.fromNanos(events.get(shownEvents - 1).timestampNanos());
                footer.append("Newer: repeat with start=\"").append(iso(newest, zone)).append("\".");
            } else footer.append("Shown every line of the window with this key.");
            String text = assemble(header, shown, footer.toString());
            if (bytes(text) <= budget) return text;
        }
        throw Errors.failure(ErrorCode.RESPONSE_BUDGET_EXCEEDED,
                "Even a minimal response does not fit maxResponseBytes of this connection. Narrow the window or raise the limit.");
    }

    /**
     * Lines of one stream selector around a moment: {@code before} lines up to it and {@code after} lines past it,
     * counted in lines rather than time, so the result does not depend on how busy the service is.
     * Lines at the moment itself (within the precision of {@code time}) are marked with {@code >>>}.
     */
    public String context(String connection, String selector, String time, Integer before, Integer after) {
        var definition = registry.require(connection);
        if (selector == null || selector.isBlank())
            throw Errors.invalid("selector is required: the stream selector of the line, e.g. {app=\"backend\"}.");
        if (selector.length() > DiscoveryLimits.SELECTOR_CHARACTERS || !DiscoveryService.SELECTOR.matcher(selector).matches())
            throw Errors.invalid("selector must be a stream selector only, like {app=\"backend\"}: no |= filters or | json, so that "
                    + "neighbouring lines without the filtered text (e.g. stack trace continuations) are shown. "
                    + "Take it from discoverLogs or from the braces of the query you used.");
        if (time == null || time.isBlank())
            throw Errors.invalid("time is required: the time of the line as printed by queryLogs, e.g. \"10:12:03.123\", or \"2026-09-13T10:12:03.123+03:00\".");
        int maximum = definition.limits().maxEntries();
        int wantBefore = before == null ? Math.min(DEFAULT_CONTEXT, maximum - 1) : before;
        int wantAfter = after == null ? Math.min(DEFAULT_CONTEXT, maximum) : after;
        if (wantBefore < 0 || wantAfter < 0 || wantBefore >= maximum || wantAfter > maximum)
            throw Errors.invalid("before and after must be between 0 and " + (maximum - 1) + " for this connection.");
        ZoneId zone = definition.timezone();
        var point = QueryTime.point(time, clock.instant(), zone);
        Duration reach = Duration.ofSeconds(definition.limits().maxIntervalSeconds());
        String scope = selector.strip();
        // One extra line backward for the target itself; the forward page starts right after the moment.
        var earlier = fetch(connection, scope, new QueryTime.Range(point.end().minus(reach), point.end()), wantBefore + 1, LokiHttpClient.Direction.BACKWARD);
        var later = wantAfter == 0 ? List.<LogEvent>of()
                : fetch(connection, scope, new QueryTime.Range(point.end(), point.end().plus(reach)), wantAfter, LokiHttpClient.Direction.FORWARD);
        int targets = (int) earlier.stream().filter(e -> !QueryTime.fromNanos(e.timestampNanos()).isBefore(point.at())).count();
        // Without a line at the moment the spare slot holds one more old line than asked for; drop it.
        if (targets == 0 && earlier.size() > wantBefore)
            earlier = new ArrayList<>(earlier.subList(earlier.size() - wantBefore, earlier.size()));
        int beforeCount = earlier.size() - targets;
        var events = new ArrayList<LogEvent>(earlier);
        events.addAll(later);
        var lines = new ArrayList<String>(events.size());
        for (int i = 0; i < events.size(); i++) {
            String text = line(events.get(i), normalizer(definition).view(events.get(i), definition.serviceLabels()), zone, false);
            lines.add(i >= beforeCount && i < earlier.size() ? ">>> " + text : text);
        }
        String moment = DATE_TIME.format(point.at().atZone(zone)) + (point.precision().compareTo(Duration.ofSeconds(1)) < 0
                ? "." + String.format("%03d", point.at().atZone(zone).getNano() / 1_000_000) : "");
        String header = "Context in " + scope + " around " + moment + " (" + point.at().atZone(zone).getOffset() + ") — " + connection
                + ", lines: " + beforeCount + " before, " + targets + " at that time, " + later.size() + " after:";
        String placeholder = targets == 0 ? ">>> (no line at exactly this time in " + scope + "; lines before and after it follow)" : null;
        int budget = definition.limits().maxResponseBytes() - ENVELOPE_BYTES;
        // The moment stays in the middle: trim the longer side first, never the marked lines.
        int keepBefore = beforeCount, keepAfter = later.size();
        while (true) {
            var window = events.subList(beforeCount - keepBefore, earlier.size() + keepAfter);
            var marked = withDateMarkers(window, lines.subList(beforeCount - keepBefore, earlier.size() + keepAfter), zone);
            if (placeholder != null) marked.add(marked.size() - keepAfter, placeholder);
            int dropped = (beforeCount - keepBefore) + (later.size() - keepAfter);
            String text = assemble(header, marked, contextFooter(earlier, later, targets, wantBefore, wantAfter, keepBefore, keepAfter, dropped, point, reach, zone));
            if (earlier.isEmpty() && later.isEmpty())
                return why(text, connection, scope, new QueryTime.Range(point.end().minus(reach), point.end().plus(reach)));
            if (bytes(text) <= budget) return text;
            if (keepBefore == 0 && keepAfter == 0) throw Errors.failure(ErrorCode.RESPONSE_BUDGET_EXCEEDED,
                    "Even a minimal response does not fit maxResponseBytes of this connection. Narrow the selector or raise the limit.");
            if (keepAfter > keepBefore) keepAfter--;
            else keepBefore--;
        }
    }

    /**
     * Count of matching lines, optionally broken down by a label or by time buckets.
     */
    public String count(String connection, String query, String start, String end, String groupBy) {
        return count(connection, query, null, null, null, start, end, groupBy);
    }

    public String count(String connection, String query, String service, String level, String textFilter, String start, String end,
                        String groupBy) {
        var definition = registry.require(connection);
        var window = QueryTime.range(start, end, clock.instant(), definition.timezone(), definition.limits().maxIntervalSeconds());
        query = QueryIntent.resolve(definition, client, query, service, level, textFilter, window);
        requireLogQuery(query);
        ZoneId zone = definition.timezone();
        String where = query.strip() + " in " + window(window, zone) + " (" + connection + ")";
        if (groupBy == null || groupBy.isBlank()) {
            long total = sum(vector(client.queryInstant(connection, countExpression(query, window.duration(), null), window.end())));
            String text = total + " lines match " + where + ".";
            return total == 0 ? why(text, connection, query, window) : text;
        }
        if (groupBy.strip().equals("time")) return buckets(connection, query, window, zone);
        String label = groupBy.strip();
        if (!label.matches("[a-zA-Z_][a-zA-Z0-9_]*"))
            throw Errors.invalid("groupBy must be a label name (letters, digits, underscore) or \"time\".");
        var rows = new ArrayList<Map.Entry<String, Long>>();
        for (var sample : vector(client.queryInstant(connection, countExpression(query, window.duration(), label), window.end())))
            rows.add(Map.entry(sample.labels().getOrDefault(label, ""), value(sample.sample().value())));
        rows.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        long total = rows.stream().mapToLong(Map.Entry::getValue).sum();
        var text = new StringBuilder(total + " lines match " + where + ".");
        if (rows.isEmpty()) return why(text.toString(), connection, query, window);
        text.append("\nBy ").append(label).append(':');
        int width = Math.max(6, rows.stream().mapToInt(r -> r.getKey().length()).max().orElse(1));
        int shown = 0;
        for (var row : rows) {
            if (shown++ >= DEFAULT_LIMIT) {
                text.append("\n  (+").append(rows.size() - DEFAULT_LIMIT).append(" more values)");
                break;
            }
            text.append("\n  ").append(String.format("%-" + width + "s  %d", row.getKey().isEmpty() ? "(none)" : row.getKey(), row.getValue()));
        }
        return text.toString();
    }

    private String buckets(String connection, String query, QueryTime.Range window, ZoneId zone) {
        // Clock-aligned "nice" steps: Loki itself aligns metric evaluations to multiples of the step (split by interval),
        // so requesting other timestamps yields buckets nobody asked for. The edge buckets may extend past the window.
        Duration step = niceStep(window.duration(), TIME_BUCKETS);
        long seconds = step.toSeconds();
        Instant first = Instant.ofEpochSecond(Math.floorDiv(window.start().getEpochSecond(), seconds) * seconds + seconds);
        long endSecond = window.end().getEpochSecond() + (window.end().getNano() > 0 ? 1 : 0);
        Instant last = Instant.ofEpochSecond(Math.floorDiv(endSecond + seconds - 1, seconds) * seconds);
        if (!first.isBefore(last)) last = first.plus(step);
        // Each evaluation at t counts (t-step, t].
        var response = client.queryRange(connection, countExpression(query, step, null), first, last,
                registry.require(connection).limits().maxEntries(), LokiHttpClient.Direction.FORWARD, stepSeconds(step));
        if (!(response.data() instanceof LokiResponses.Matrix matrix)) throw notMetric();
        String where = query.strip() + " in " + window(new QueryTime.Range(first.minus(step), last), zone) + " (" + connection + ")";
        var counts = new TreeMap<Long, Long>();
        for (Instant t = first; !t.isAfter(last); t = t.plus(step)) counts.put(t.getEpochSecond(), 0L);
        for (var series : matrix.series())
            for (var sample : series.samples()) {
                long at = sample.timestampSeconds().setScale(0, RoundingMode.HALF_UP).longValueExact();
                counts.merge(at, value(sample.value()), Long::sum);
            }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        var text = new StringBuilder(total + " lines match " + where + ".");
        if (total == 0) return why(text.toString(), connection, query, window);
        var sorted = new ArrayList<>(counts.values());
        Collections.sort(sorted);
        double median = sorted.get(sorted.size() / 2);
        double reference = median > 0 ? median : (double) total / sorted.size();
        var format = step.toSeconds() < 60 ? DateTimeFormatter.ofPattern("HH:mm:ss") : DateTimeFormatter.ofPattern("HH:mm");
        text.append("\nBy time (").append(QueryTime.lokiDuration(step)).append(" buckets, bucket start):");
        for (var bucket : counts.entrySet()) {
            Instant bucketStart = Instant.ofEpochSecond(bucket.getKey()).minus(step);
            text.append("\n  ").append(format.format(bucketStart.atZone(zone))).append(String.format("  %6d", bucket.getValue()));
            if (bucket.getValue() >= 5 && bucket.getValue() >= 3 * reference) text.append("  <- spike");
        }
        return text.toString();
    }

    /**
     * Range metric query as a table: one block per series, one row per point.
     */
    public String metrics(String connection, String query, String start, String end, String step) {
        var definition = registry.require(connection);
        if (query == null || query.isBlank())
            throw Errors.invalid("query is required: a metric LogQL expression such as sum(rate({app=\"x\"}[5m])).");
        if (query.strip().startsWith("{"))
            throw Errors.invalid("This looks like a log query. Use queryLogs to read lines or countLogs to count them; "
                    + "queryMetrics needs a metric expression such as sum by (level) (count_over_time({app=\"x\"}[5m])).");
        var window = QueryTime.range(start, end, clock.instant(), definition.timezone(), definition.limits().maxIntervalSeconds());
        Duration used = step == null || step.isBlank() ? niceStep(window.duration()) : QueryTime.duration(step);
        long evaluations = window.duration().toMillis() / used.toMillis() + 1;
        if (evaluations > definition.limits().maxMetricPoints())
            throw Errors.invalid("step \"" + QueryTime.lokiDuration(used) + "\" gives " + evaluations + " points per series; use a larger step or a shorter window.");
        var response = client.queryRange(connection, query, window.start(), window.end(), definition.limits().maxEntries(),
                LokiHttpClient.Direction.FORWARD, stepSeconds(used));
        if (!(response.data() instanceof LokiResponses.Matrix matrix)) throw notMetric();
        ZoneId zone = definition.timezone();
        var text = new StringBuilder(query.strip() + " — " + connection + ", " + window(window, zone) + ", step " + QueryTime.lokiDuration(used));
        if (matrix.series().isEmpty())
            return text.append(": no series. The expression matched no data in this window.").toString();
        int maxSeries = definition.limits().maxMetricSeries(), maxPoints = definition.limits().maxMetricPoints(), points = 0, shownSeries = 0;
        var format = used.toSeconds() < 60 ? DateTimeFormatter.ofPattern("HH:mm:ss") : DateTimeFormatter.ofPattern("MM-dd HH:mm");
        text.append(", ").append(matrix.series().size()).append(matrix.series().size() == 1 ? " series:" : " series:");
        boolean trimmed = false;
        for (var series : matrix.series()) {
            if (shownSeries >= maxSeries || points >= maxPoints) {
                trimmed = true;
                break;
            }
            text.append('\n').append(labels(series.labels()));
            for (var sample : series.samples()) {
                if (points >= maxPoints) {
                    trimmed = true;
                    break;
                }
                text.append("\n  ").append(format.format(Instant.ofEpochSecond(sample.timestampSeconds().setScale(0, RoundingMode.DOWN).longValueExact()).atZone(zone)))
                        .append("  ").append(sample.value());
                points++;
            }
            shownSeries++;
        }
        if (trimmed) text.append("\nOutput trimmed to ").append(shownSeries).append(" series / ").append(points)
                .append(" points (connection limits). Aggregate with sum by (...) or use a larger step.");
        return fit(text.toString(), List.of(), ignored -> "", definition.limits().maxResponseBytes() - ENVELOPE_BYTES);
    }

    Sample sample(String connection, String query, QueryTime.Range window, int wanted, int maxHttpResponseBytes) {
        var collected = new ArrayList<LogEvent>();
        long bytes = 0;
        Instant end = window.end();
        int page = Math.min(wanted, FIRST_PAGE);
        while (true) {
            // Loki's end is exclusive, so the next page starts 1 ns after the oldest line read and returns the lines of
            // that instant again. They are asked for on top of the page and dropped, so that an instant holding more
            // lines than a page is read through instead of being returned over and over.
            var held = new ArrayList<LogEvent>();
            if (!collected.isEmpty()) {
                long boundary = collected.getFirst().nanos();
                for (var event : collected)
                    if (event.nanos() == boundary) held.add(event);
                    else break;
            }
            int limit = page + held.size();
            var events = fetch(connection, query, new QueryTime.Range(window.start(), end), limit, LokiHttpClient.Direction.BACKWARD);
            int returned = events.size();
            if (!held.isEmpty()) {
                long boundary = held.getFirst().nanos();
                events.removeIf(e -> e.nanos() == boundary && held.remove(e));
            }
            if (events.isEmpty()) break;
            collected.addAll(0, events);
            for (var event : events) bytes += event.line().length();
            if (collected.size() >= wanted) {
                collected = new ArrayList<>(collected.subList(collected.size() - wanted, collected.size()));
                break;
            }
            if (returned < limit) break;
            if (bytes >= maxHttpResponseBytes) return new Sample(collected, bytes, true);
            long average = Math.max(1, bytes / collected.size());
            page = (int) Math.max(1, Math.min(wanted - collected.size(), maxHttpResponseBytes / 2 / average));
            end = QueryTime.fromNanos(collected.getFirst().timestampNanos()).plusNanos(1);
            if (!end.isAfter(window.start())) break;
        }
        return new Sample(collected, bytes, false);
    }

    List<LogEvent> fetch(String connection, String query, QueryTime.Range window, int limit, LokiHttpClient.Direction direction) {
        var response = client.queryRange(connection, query, window.start(), window.end(), limit, direction, null);
        if (!(response.data() instanceof LokiResponses.Streams streams))
            throw Errors.invalid("This is a metric expression; queryLogs reads log lines. Use queryMetrics for metric LogQL.");
        var events = new ArrayList<LogEvent>();
        for (var stream : streams.streams())
            for (var entry : stream.entries())
                events.add(new LogEvent(entry.timestampNanos(), stream.labels(), entry.line(), entry.structuredMetadata()));
        events.sort(Comparator.comparingLong(LogEvent::nanos)); // Stable: identical timestamps keep upstream order and multiplicity.
        // A page larger than the limit is trimmed on the side the direction did not favour.
        if (events.size() <= limit) return events;
        return new ArrayList<>(direction == LokiHttpClient.Direction.BACKWARD ? events.subList(events.size() - limit, events.size()) : events.subList(0, limit));
    }

    /**
     * The header line of the groups' history (null when no group could be counted), and the groups of the same hours a
     * day earlier that are gone: {@code goneTitle} is null when there is nothing to say, {@code gone} null when the
     * request failed.
     */
    record Past(String header, String goneTitle, List<LogSummary.Group> gone,
                List<IncidentPicture.Incident> incidents) {
    }

    /**
     * The newest lines of a window, read in pages sized by the lines seen so far, so that a sample of 16 KB error
     * lines never asks Loki for one response above maxHttpResponseBytes. Stops at {@code wanted} lines, at the end
     * of the window, or when the lines read reach the connection's HTTP byte limit ({@code cutByBytes}).
     */
    record Sample(List<LogEvent> events, long bytes, boolean cutByBytes) {
    }
}
