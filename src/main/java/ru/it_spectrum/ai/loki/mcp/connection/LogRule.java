package ru.it_spectrum.ai.loki.mcp.connection;

import ru.it_spectrum.ai.loki.mcp.service.Errors;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One entry of a connection's rules catalogue: what a known kind of line means. All given patterns must match (they
 * are searched, not anchored); at least one is required. {@code exception} is tried against the simple names of the
 * root cause and every wrapper, {@code message} against the logged message, the root cause and the wrapper messages, {@code logger}
 * against the logger name. {@code subject} and {@code advice} may use {@code ${name}} of named groups of
 * {@code message}. {@code filter} is a LogQL line filter that excludes the lines, offered when noise crowds a sample.
 */
public record LogRule(String id, Category category, Pattern exception, Pattern message, Pattern logger,
                      String subject, String advice, String filter) {
    public static final int MAX_PATTERN_CHARS = 1000;
    public static final int MAX_TEXT_CHARS = 300;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-zA-Z][a-zA-Z0-9]*)}");
    private static final Pattern FILTER = Pattern.compile("(?:\\s*(?:!=|!~)\\s*\"(?:[^\"\\\\]|\\\\.)+\")+\\s*");
    public LogRule {
        if (id == null || !ID.matcher(id).matches() || category == null
                || (exception == null && message == null && logger == null)
                || (subject != null && (subject.isBlank() || subject.length() > MAX_TEXT_CHARS))
                || (category == Category.DEPENDENCY && subject == null)
                || advice == null || advice.isBlank() || advice.length() > MAX_TEXT_CHARS
                || (filter != null && !FILTER.matcher(filter).matches())
                || !placeholdersResolvable(subject, message) || !placeholdersResolvable(advice, message)) {
            throw Errors.configuration();
        }
    }

    public static Pattern compile(String regex) {
        if (regex == null) return null;
        if (regex.isEmpty() || regex.length() > MAX_PATTERN_CHARS) throw Errors.configuration();
        try {
            return Pattern.compile(regex);
        } catch (RuntimeException ignored) {
            throw Errors.configuration();
        }
    }

    private static boolean placeholdersResolvable(String text, Pattern message) {
        if (text == null) return true;
        var matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            if (message == null || !message.namedGroups().containsKey(matcher.group(1))) return false;
        }
        return true;
    }

    /**
     * {@code text} with {@code ${name}} replaced by the named groups of a successful {@code message} match.
     */
    public static String expand(String text, Matcher matched) {
        if (text == null || matched == null) return text;
        var matcher = PLACEHOLDER.matcher(text);
        var result = new StringBuilder();
        while (matcher.find()) {
            String value = matched.group(matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(value == null ? "?" : value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    @Override
    public String toString() {
        return "LogRule[" + id + "]";
    }

    public enum Category {
        /**
         * Another system the service depends on failed: a database, a queue, a neighbouring service, an agency.
         */
        DEPENDENCY,
        /**
         * The service did not start or stopped at start-up.
         */
        STARTUP,
        /**
         * The service's own configuration is wrong or missing.
         */
        CONFIGURATION,
        /**
         * Known harmless lines: client aborts, scanners, missing endpoints polled by old clients.
         */
        NOISE;

        public String text() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
