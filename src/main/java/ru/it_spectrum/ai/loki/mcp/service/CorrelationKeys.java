package ru.it_spectrum.ai.loki.mcp.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Identifiers a line carries that other lines may carry too: {@code name=value} or {@code name: value} pairs in the
 * message whose name ends in {@code Id}, {@code ID} or {@code _id} ({@code taskExecutionId=13548},
 * {@code ErrorID: ERR-…}, {@code objectId = 81302}), and the trace id of the line. Names come from the line; the code
 * knows no stand's vocabulary. On a stand without tracing, such keys are what ties the lines of one failure together.
 */
public final class CorrelationKeys {
    public record Key(String name, String value) {
        public String text() {
            return name + "=" + value;
        }
    }

    static final int KEYS_PER_LINE = 5;
    static final int MESSAGE_CHARS = 2000;
    private static final Pattern PAIR = Pattern.compile(
            "(?<![\\w.])([A-Za-z][A-Za-z0-9_]{0,40}?(?:Id|ID|_id))\\s*[=:]\\s*\"?([A-Za-z0-9][A-Za-z0-9_\\-]{2,63})(?![\\w\\-])");

    private CorrelationKeys() {
    }

    public static List<Key> of(EventNormalizer.View view) {
        var keys = new LinkedHashSet<Key>();
        if (view.traceId() != null && view.traceId().length() >= 3) keys.add(new Key("traceId", view.traceId()));
        String message = view.message() == null ? "" : view.message();
        if (message.length() > MESSAGE_CHARS) message = message.substring(0, MESSAGE_CHARS);
        var matcher = PAIR.matcher(message);
        while (matcher.find() && keys.size() < KEYS_PER_LINE) keys.add(new Key(matcher.group(1), matcher.group(2)));
        return new ArrayList<>(keys);
    }

    /**
     * Matches the value as a whole token, so that 13548 is not found inside 135480 or ERR-13548x.
     */
    public static Pattern token(String value) {
        return Pattern.compile("(?<![A-Za-z0-9_\\-])" + Pattern.quote(value) + "(?![A-Za-z0-9_\\-])");
    }
}
