package ru.it_spectrum.ai.loki.mcp.service;

import ru.it_spectrum.ai.loki.mcp.client.LokiResponses;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Whether a group of {@code summarizeLogs} happened before: Loki counts the lines of the query that hold a literal
 * fragment of the group's message, in the 7 days before the window and at the same hours on each of those days.
 * Counts only; no line of the past is downloaded. One metric request covers many groups: the fragments are one
 * alternation, and {@code | regexp} turns the fragment a line holds into a label to count by. Rules and the
 * measurements behind them: docs/decisions.md, "Baseline and field contrast".
 */
final class GroupHistory {
    static final int DAYS = 7;
    static final long DAY_SECONDS = 86_400;
    static final int FRAGMENT_MIN = 8;
    static final int FRAGMENT_CHARS = 60;
    /**
     * The URL-encoded query of one request stays under this, so that an ingress with 8 KB request lines passes it
     * (a Cyrillic character is 6 bytes encoded); more groups go into more requests.
     */
    static final int QUERY_BYTES = 6000;
    /**
     * No new request starts after this much time of the summary's history; the groups left say so.
     */
    static final Duration DEADLINE = Duration.ofSeconds(20);
    /**
     * Longer windows are not counted at the same hours of 7 days (8 windows of data, 27 s for a day on the asva2 DEV
     * Loki 2.6.1) but only in the window itself, against the daily counts scaled to its length.
     */
    static final Duration SAME_HOURS = Duration.ofHours(6);
    static final double MORE_RATIO = 3;
    static final double MORE_P = 0.01;
    static final String LABEL = "mcp_fragment";
    static final String NOT_CHECKED = "history not checked";
    /**
     * Stands for the fragments in the server's own log: they are text of the stand's log lines.
     */
    static final String LOGGED_FRAGMENTS = "<log text>";
    private static final Pattern REGEX_META = Pattern.compile("[\\\\.^$|?*+()\\[\\]{}]");

    private GroupHistory() {
    }

    /**
     * Groups counted by one request: their fragments, the scope (selector, the query's pipeline and the fragment
     * stages), the same scope for the log, and the labels to count by.
     */
    record Batch(Map<LogSummary.Group, String> fragments, String scope, String logged, List<String> by) {
    }

    /**
     * A metric request evaluated every day from {@code start} to {@code end} (both multiples of a day, as Loki aligns
     * the steps of a range query to multiples of the step anyway).
     */
    record Request(String expression, String logged, Instant start, Instant end) {
    }

    enum Kind {NEW, MORE, SEEN}

    /**
     * {@code window} and {@code usual} are -1 when the window was not counted; {@code usual} is the median of the same
     * hours ({@code per} "at these hours") or of the daily counts scaled to the window ({@code per} "in 12h").
     */
    record Verdict(Kind kind, long previous, long window, long usual, String per) {
        String text() {
            String usually = "usually " + (per.startsWith("in ") ? "about " : "") + usual + " " + per;
            return switch (kind) {
                case NEW -> "new: not seen in the " + DAYS + " days before";
                case MORE -> "more than usual: " + window + " in this window, " + usually + "; " + previous + " in the " + DAYS + " days before";
                case SEEN -> "seen before: " + previous + " in the " + DAYS + " days before" + (usual < 0 ? "" : ", " + usually);
            };
        }
    }

    /**
     * The longest run of the first message line between normalized parts, as it appears in the raw line (JSON-escaped
     * for a JSON line), cut at a word boundary; the simple root type when no run is long enough; null when the group
     * has no text to count by.
     */
    static String fragment(LogSummary.Group group) {
        var view = group.lastView;
        var signature = group.lastSignature;
        if (group.template.equals(LogSummary.FRAMES_TEMPLATE)) return null;
        String message = signature != null ? signature.rootMessage() : view.message();
        String best = longestRun(message == null ? "" : message.strip().lines().findFirst().orElse(""));
        if (best.length() < FRAGMENT_MIN) {
            if (signature == null) return null;
            best = signature.rootType();
        }
        if (view.format() == EventNormalizer.Format.JSON) best = jsonEscape(best);
        if (best.length() > FRAGMENT_CHARS) {
            int space = best.lastIndexOf(' ', FRAGMENT_CHARS);
            best = space >= FRAGMENT_MIN ? best.substring(0, space) : best.substring(0, FRAGMENT_CHARS);
            // Never end inside an escape sequence: an odd run of trailing backslashes is cut off.
            int slashes = 0;
            while (slashes < best.length() && best.charAt(best.length() - 1 - slashes) == '\\') slashes++;
            if (slashes % 2 == 1) best = best.substring(0, best.length() - 1);
        }
        best = best.strip();
        return best.length() < FRAGMENT_MIN && signature == null ? null : best;
    }

    private static String longestRun(String line) {
        String best = "";
        for (String part : LogSummary.normalize(line).split("\\*"))
            if (part.strip().length() > best.length()) best = part.strip();
        return best;
    }

    private static String jsonEscape(String text) {
        var result = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') result.append('\\');
            result.append(c);
        }
        return result.toString();
    }

    /**
     * The groups with a fragment, in order, packed into requests under {@link #QUERY_BYTES}; empty when the query has
     * no stream selector to reuse.
     */
    static List<Batch> batches(String query, List<LogSummary.Group> groups) {
        String text = query.strip();
        var selector = DiscoveryService.SELECTOR.matcher(text);
        if (!selector.lookingAt()) return List.of();
        String braces = text.substring(0, selector.end()).strip(), pipeline = text.substring(selector.end()).strip();
        var batches = new ArrayList<Batch>();
        var current = new LinkedHashMap<LogSummary.Group, String>();
        for (var group : groups) {
            String fragment = fragment(group);
            if (fragment == null) continue;
            current.put(group, fragment);
            // The longest expression of a batch is the same-hours one; one group always makes a batch of its own.
            if (current.size() > 1 && encoded(expression(batch(braces, pipeline, current), DAY_SECONDS, DAY_SECONDS)) > QUERY_BYTES) {
                current.remove(group);
                batches.add(batch(braces, pipeline, current));
                current = new LinkedHashMap<>();
                current.put(group, fragment);
            }
        }
        if (!current.isEmpty()) batches.add(batch(braces, pipeline, current));
        return batches;
    }

    /**
     * The selector narrowed to the groups' services when all of them took their service from one stream label, the
     * query's pipeline, a literal alternation of the fragments (a substring search in Loki) and the same alternation
     * as a named group of {@code | regexp}, longest first, so that the fragment a line holds becomes its label.
     */
    private static Batch batch(String braces, String pipeline, Map<LogSummary.Group, String> fragments) {
        var labels = new TreeSet<String>();
        boolean narrow = true;
        var values = new TreeSet<String>();
        for (var group : fragments.keySet()) {
            if (group.serviceLabel == null) narrow = false;
            else {
                labels.add(group.serviceLabel);
                values.addAll(group.serviceValues);
            }
        }
        String selector = braces;
        if (narrow && labels.size() == 1) {
            String label = labels.first();
            String matcher = values.size() == 1 ? label + "=\"" + logqlEscape(values.first()) + "\""
                    : label + "=~\"" + logqlEscape(String.join("|", values.stream().map(GroupHistory::regexEscape).toList())) + "\"";
            selector = braces.substring(0, braces.length() - 1).stripTrailing() + ", " + matcher + "}";
        }
        var alternatives = new ArrayList<>(new LinkedHashSet<>(fragments.values()));
        alternatives.sort(Comparator.comparingInt(String::length).reversed());
        String alternation = logqlEscape(String.join("|", alternatives.stream().map(GroupHistory::regexEscape).toList()));
        String base = selector + (pipeline.isEmpty() ? "" : " " + pipeline);
        var by = new ArrayList<String>();
        by.add(LABEL);
        by.addAll(labels);
        return new Batch(Map.copyOf(fragments), base + stages(alternation), base + stages(LOGGED_FRAGMENTS), List.copyOf(by));
    }

    private static String stages(String alternation) {
        return " |~ \"" + alternation + "\" | regexp \"(?P<" + LABEL + ">" + alternation + ")\"";
    }

    static String regexEscape(String text) {
        return REGEX_META.matcher(text).replaceAll("\\\\$0");
    }

    static String logqlEscape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static int encoded(String expression) {
        return URLEncoder.encode(expression, StandardCharsets.UTF_8).length();
    }

    /**
     * Seven periods of 24 hours ending at the window start, newest at {@code end}: the evaluation at
     * {@code end - k days} counts (start - (k+1) days, start - k days].
     */
    static Request previousDays(Batch batch, QueryTime.Range window) {
        long start = window.start().getEpochSecond();
        long aligned = Math.ceilDiv(start, DAY_SECONDS) * DAY_SECONDS;
        return request(batch, DAY_SECONDS, aligned - start, aligned - (DAYS - 1) * DAY_SECONDS, aligned);
    }

    /**
     * The window itself (at {@code end}) and, for a window of at most {@link #SAME_HOURS}, the same hours on each of
     * the 7 days before; a longer window gets one day before it as the second point, which is not used.
     */
    static Request sameHours(Batch batch, QueryTime.Range window) {
        // Whole seconds from the window start; "now-24h … now" stays one day although its ends carry nanoseconds.
        long start = window.start().getEpochSecond();
        long length = Math.max(1, window.duration().plusNanos(999_999_999).getSeconds());
        long end = start + length;
        long aligned = Math.ceilDiv(end, DAY_SECONDS) * DAY_SECONDS;
        int days = window.duration().compareTo(SAME_HOURS) <= 0 ? DAYS : 1;
        return request(batch, length, aligned - end, aligned - days * DAY_SECONDS, aligned);
    }

    private static Request request(Batch batch, long rangeSeconds, long offsetSeconds, long start, long end) {
        return new Request(expression(batch, rangeSeconds, offsetSeconds), expression(new Batch(batch.fragments(), batch.logged(), batch.logged(), batch.by()),
                rangeSeconds, offsetSeconds), Instant.ofEpochSecond(start), Instant.ofEpochSecond(end));
    }

    private static String expression(Batch batch, long rangeSeconds, long offsetSeconds) {
        return "sum by (" + String.join(", ", batch.by()) + ") (count_over_time(" + batch.scope() + " [" + rangeSeconds + "s]"
                + (offsetSeconds > 0 ? " offset " + offsetSeconds + "s" : "") + "))";
    }

    static BigDecimal step() {
        return BigDecimal.valueOf(DAY_SECONDS);
    }

    /**
     * Counts by day for every group of the batch, index 0 at the request's end: the series of the group's fragment
     * and, when its service came from a label, of its services. Days Loki returned no point for are 0.
     */
    static Map<LogSummary.Group, long[]> counts(Batch batch, Request request, LokiResponses.QueryResponse response) {
        if (response == null || !(response.data() instanceof LokiResponses.Matrix matrix)) throw new IllegalStateException("not a matrix");
        long end = request.end().getEpochSecond();
        int days = (int) ((end - request.start().getEpochSecond()) / DAY_SECONDS) + 1;
        var counts = new HashMap<LogSummary.Group, long[]>();
        for (var group : batch.fragments().keySet()) counts.put(group, new long[days]);
        for (var series : matrix.series()) {
            String fragment = series.labels().get(LABEL);
            for (var entry : batch.fragments().entrySet()) {
                var group = entry.getKey();
                if (!entry.getValue().equals(fragment)
                        || (group.serviceLabel != null && !group.serviceValues.contains(series.labels().get(group.serviceLabel)))) continue;
                for (var sample : series.samples()) {
                    long at = sample.timestampSeconds().setScale(0, RoundingMode.HALF_UP).longValueExact();
                    if ((end - at) % DAY_SECONDS == 0 && end >= at && (end - at) / DAY_SECONDS < days)
                        counts.get(group)[(int) ((end - at) / DAY_SECONDS)] += value(sample.value());
                }
            }
        }
        return counts;
    }

    private static long value(String metric) {
        try {
            return new BigDecimal(metric).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (NumberFormatException | ArithmeticException ignored) {
            return 0;
        }
    }

    /**
     * {@code previous}: the 7 periods of 24 hours before the window; {@code same}: the window, then the same hours of
     * the 7 days before (a window of at most {@link #SAME_HOURS}) or one unused point, or null when not counted.
     */
    static Verdict classify(long[] previous, long[] same, Duration window) {
        long total = Arrays.stream(previous).sum();
        if (total == 0) return new Verdict(Kind.NEW, 0, -1, -1, "");
        if (same == null) return new Verdict(Kind.SEEN, total, -1, -1, "");
        long now = same[0];
        long usual;
        String per;
        if (same.length > DAYS) {
            usual = median(Arrays.copyOfRange(same, 1, same.length));
            per = "at these hours";
        } else {
            usual = Math.round(median(previous) * (double) window.getSeconds() / DAY_SECONDS);
            per = "in " + QueryTime.human(Duration.ofSeconds(window.getSeconds()));
        }
        boolean more = now >= MORE_RATIO * Math.max(usual, 1) && poissonTail(now, Math.max(usual, 0.5)) < MORE_P;
        return new Verdict(more ? Kind.MORE : Kind.SEEN, total, now, usual, per);
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    /**
     * P(X ≥ k) for X ~ Poisson(λ).
     */
    static double poissonTail(long k, double lambda) {
        double term = Math.exp(-lambda), below = 0;
        for (long i = 0; i < k; i++) {
            below += term;
            term = term * lambda / (i + 1);
        }
        return Math.max(0, 1 - below);
    }

    /**
     * {@code Compared with the 7 days before (lines of this query with the same text): 4 groups new, …}.
     */
    static String header(Collection<Verdict> verdicts, int notChecked) {
        long fresh = verdicts.stream().filter(v -> v.kind() == Kind.NEW).count();
        long more = verdicts.stream().filter(v -> v.kind() == Kind.MORE).count();
        long seen = verdicts.stream().filter(v -> v.kind() == Kind.SEEN).count();
        String text = verdicts.isEmpty() ? "History of the groups was not checked"
                : "Compared with the " + DAYS + " days before (lines of this query with the same text): " + fresh + (fresh == 1 ? " group" : " groups")
                + " new, " + more + " more than usual, " + seen + " seen before";
        if (!verdicts.isEmpty() && notChecked > 0) text += "; " + notChecked + " not checked";
        return text + (notChecked > 0 ? " (Loki did not answer in time or refused the count)." : ".");
    }
}
