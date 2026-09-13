package ru.it_spectrum.ai.loki.mcp.service;

import java.util.Map;
import java.util.TreeMap;
import org.slf4j.MDC;

/**
 * Formatting for the server's own log: what was asked, how it went, how long it took.
 * Argument values are the model's own text (queries, selectors, times) and are logged as such, cut to a fixed length;
 * connection URLs, credentials, tenant and log line contents never pass through here.
 */
public final class Diagnostics {
    public static final String MDC_CONNECTION = "connection";
    public static final int VALUE_CHARS = 200;
    private Diagnostics() {}

    /** {@code {key=value, ...}} in key order, each value flattened to one line and truncated. */
    public static String arguments(Map<String, ?> arguments) {
        var text = new StringBuilder("{");
        for (var pair : new TreeMap<>(arguments).entrySet()) {
            if (text.length() > 1) text.append(", ");
            text.append(pair.getKey()).append('=').append(value(pair.getValue()));
        }
        return text.append('}').toString();
    }

    public static String value(Object value) {
        String text = String.valueOf(value).replace('\n', ' ').replace('\r', ' ');
        return text.codePointCount(0, text.length()) <= VALUE_CHARS ? text : text.substring(0, text.offsetByCodePoints(0, VALUE_CHARS)) + "…";
    }

    public static long millisSince(long startNanos) { return (System.nanoTime() - startNanos) / 1_000_000; }

    /** Puts the connection into the MDC for the duration of a call; the returned value restores the previous state. */
    public static String enter(String connection) {
        String previous = MDC.get(MDC_CONNECTION);
        if (connection == null || connection.isBlank()) MDC.remove(MDC_CONNECTION); else MDC.put(MDC_CONNECTION, connection);
        return previous;
    }

    public static void leave(String previous) {
        if (previous == null) MDC.remove(MDC_CONNECTION); else MDC.put(MDC_CONNECTION, previous);
    }
}
