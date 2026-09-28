package ru.it_spectrum.ai.loki.mcp.service;

import ru.it_spectrum.ai.loki.mcp.error.ErrorCode;
import ru.it_spectrum.ai.loki.mcp.error.Errors;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Shared text rendering and response byte budget for tool replies.
 */
public final class ResponseText {
    /**
     * Reserved for the JSON-RPC envelope, escaping and the request id around the text payload.
     */
    public static final int ENVELOPE_BYTES = 512;
    public static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ResponseText() {
    }

    public static String truncate(String text, int maxChars) {
        if (text.codePointCount(0, text.length()) <= maxChars) return text;
        int end = text.offsetByCodePoints(0, maxChars);
        return text.substring(0, end) + "…";
    }

    public static String labels(Map<String, String> labels) {
        var parts = new ArrayList<String>();
        for (var label : new TreeMap<>(labels).entrySet()) parts.add(label.getKey() + "=\"" + label.getValue() + "\"");
        return "{" + String.join(", ", parts) + "}";
    }

    public static String window(QueryTime.Range range, ZoneId zone) {
        var start = range.start().atZone(zone);
        var end = range.end().atZone(zone);
        String endText = start.toLocalDate().equals(end.toLocalDate()) ? end.format(DateTimeFormatter.ofPattern("HH:mm:ss")) : end.format(DATE_TIME);
        return start.format(DATE_TIME) + "–" + endText + " (" + start.getOffset() + ")";
    }

    public static int bytes(CharSequence text) {
        return text.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Drops the oldest lines (front of the list) until header + lines + footer fit the byte budget.
     */
    public static String fit(String header, List<String> lines, Function<Integer, String> footerForDropped, int budgetBytes) {
        var kept = new ArrayList<>(lines);
        int dropped = 0;
        while (true) {
            String text = assemble(header, kept, footerForDropped.apply(dropped));
            // A page that had lines must keep at least one; an empty page is fine only when there was nothing to show.
            if (bytes(text) <= budgetBytes && (!kept.isEmpty() || lines.isEmpty())) return text;
            if (kept.isEmpty()) throw Errors.failure(ErrorCode.RESPONSE_BUDGET_EXCEEDED,
                    "Even a minimal response does not fit maxResponseBytes of this connection. Narrow the query or raise the limit.");
            kept.removeFirst();
            dropped++;
        }
    }

    public static String assemble(String header, List<String> lines, String footer) {
        var text = new StringBuilder(header);
        for (String line : lines) text.append('\n').append(line);
        if (footer != null && !footer.isEmpty()) text.append('\n').append(footer);
        return text.toString();
    }

    public static String iso(Instant time, ZoneId zone) {
        return QueryTime.iso(time, zone);
    }
}
