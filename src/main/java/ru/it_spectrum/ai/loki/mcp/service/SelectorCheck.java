package ru.it_spectrum.ai.loki.mcp.service;

import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Why a query found no line: a label that does not exist, a value no stream has (with the closest ones), matchers no
 * stream holds together, or a filter that dropped every line of the streams. Asked only after an empty result, with
 * cheap metadata requests (label values, labels, series); a failure of any of them leaves the plain empty answer.
 */
final class SelectorCheck {
    static final int CLOSEST = 5;
    static final int LABELS_SHOWN = 20;
    private static final Pattern MATCHER = Pattern.compile(
            "([a-zA-Z_][a-zA-Z0-9_]*)\\s*(=~|!~|!=|=)\\s*\"((?:[^\"\\\\\\r\\n]|\\\\[^\\r\\n])*+)\"");
    private final LokiHttpClient client;

    SelectorCheck(LokiHttpClient client) {
        this.client = client;
    }

    record Label(String name, String operator, String value) {
    }

    /**
     * The matchers of the query's stream selector, values unescaped; empty when the query does not start with one.
     */
    static List<Label> matchers(String query) {
        var selector = DiscoveryService.SELECTOR.matcher(query == null ? "" : query);
        if (!selector.lookingAt()) return List.of();
        var result = new ArrayList<Label>();
        Matcher matcher = MATCHER.matcher(query.substring(0, selector.end()));
        while (matcher.find())
            result.add(new Label(matcher.group(1), matcher.group(2), matcher.group(3).replaceAll("\\\\(.)", "$1")));
        return result;
    }

    /**
     * One sentence starting with {@code Why:}, or null when the reason could not be found: the equality matchers are
     * looked up one by one, then the selector as a whole, then the pipeline after it is blamed.
     */
    String explain(String connection, String query, QueryTime.Range window) {
        var selector = DiscoveryService.SELECTOR.matcher(query == null ? "" : query);
        if (!selector.lookingAt()) return null;
        String braces = query.substring(0, selector.end()).strip();
        String pipeline = query.substring(selector.end()).strip();
        try {
            List<String> labels = null;
            for (var label : matchers(query)) {
                if (!label.operator().equals("=")) continue;
                var values = client.labelValues(connection, label.name(), window.start(), window.end(), null);
                if (values == null) return null;
                if (values.values().contains(label.value())) continue;
                if (values.values().isEmpty()) {
                    if (labels == null) {
                        var response = client.labels(connection, window.start(), window.end(), null);
                        if (response == null) return null;
                        labels = new ArrayList<>(new TreeSet<>(response.values()));
                    }
                    if (!labels.contains(label.name()))
                        return "Why: label " + label.name() + " does not exist in this window; labels: " + list(labels, LABELS_SHOWN)
                                + ". Take labels and values from discoverLogs.";
                    return "Why: label " + label.name() + " has no values in this window. Try a wider window (e.g. start=\"now-24h\").";
                }
                return "Why: no stream has " + label.name() + "=\"" + label.value() + "\" in this window; closest values of " + label.name()
                        + ": " + String.join(", ", closest(label.value(), values.values())) + ". discoverLogs with label=\"" + label.name()
                        + "\" lists them all.";
            }
            var series = client.series(connection, List.of(braces), window.start(), window.end());
            if (series == null) return null;
            int streams = series.streams().size();
            if (streams == 0)
                return "Why: no stream matches " + braces + " in this window, although every single value exists; one matcher "
                        + "excludes the others. Drop matchers one by one, or take a selector from discoverLogs.";
            String of = streams + (streams == 1 ? " stream" : " streams");
            if (pipeline.isEmpty())
                return "Why: " + braces + " matches " + of + ", but they have no line in this window. Try a wider window.";
            return "Why: " + braces + " matches " + of + ", but " + LogText.truncate(pipeline, 200) + " left no line. Check the filter "
                    + "text (it is case-sensitive; |~ \"(?i)...\" ignores case); countLogs with " + braces + " alone shows how many lines "
                    + "the streams have.";
        } catch (LokiOperationException e) {
            if (e.error().code() == ErrorCode.OPERATION_CANCELLED) throw e;
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Values nearest to {@code wanted}: holding it or held by it first (ignoring case), then by edit distance.
     */
    static List<String> closest(String wanted, Collection<String> values) {
        String lower = wanted.toLowerCase(Locale.ROOT);
        var sorted = new ArrayList<>(new TreeSet<>(values));
        sorted.sort(Comparator.comparingInt((String v) -> {
                    String candidate = v.toLowerCase(Locale.ROOT);
                    return candidate.contains(lower) || (!candidate.isEmpty() && lower.contains(candidate)) ? 0 : 1;
                })
                .thenComparingInt(v -> distance(lower, v.toLowerCase(Locale.ROOT))));
        return sorted.subList(0, Math.min(CLOSEST, sorted.size()));
    }

    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1], current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++)
                current[j] = Math.min(Math.min(current[j - 1], previous[j]) + 1, previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            var swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static String list(List<String> values, int shown) {
        return String.join(", ", values.subList(0, Math.min(shown, values.size())))
                + (values.size() > shown ? " (+" + (values.size() - shown) + " more)" : "");
    }
}
