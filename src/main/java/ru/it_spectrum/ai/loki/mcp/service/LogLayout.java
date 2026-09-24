package ru.it_spectrum.ai.loki.mcp.service;

import ru.it_spectrum.ai.loki.mcp.model.LogEvent;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A line template of exportLogs: text with {@code {name}} placeholders, the reverse of a line format. {@code time},
 * {@code level}, {@code service}, {@code logger}, {@code message}, {@code traceId} and {@code line} (the original line)
 * come from the normalized line; {@code stack} is a newline and the full stack trace, or nothing. Any other name is a
 * field of the line (a dotted JSON path or a group of a line format), then a stream label, then structured metadata.
 * {@code {a|b}} takes the first name that has a value; {@code {level:5}} pads on the left to 5 characters,
 * {@code {logger:-40}} on the right; {@code {time:HH:mm:ss.SSS}} takes a date-time pattern. {@code {{} and {@code }}}
 * are literal braces. A missing value is empty. A plain-text line that no line format of the connection splits is
 * written unchanged, unless the template holds {@code {line}}: a layout rewrites fields it knows, never the text of an
 * unknown layout or a stack frame logged on its own line.
 */
public final class LogLayout {
    public static final DateTimeFormatter DEFAULT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    public static final int MAX_TEMPLATE_CHARS = 500;
    private static final Pattern PLACEHOLDER = Pattern.compile("([A-Za-z0-9_.@-]+(?:\\|[A-Za-z0-9_.@-]+)*)(?::(.+))?");
    private static final Pattern WIDTH = Pattern.compile("-?\\d{1,3}");
    private static final List<String> VIEW_NAMES = List.of("level", "service", "logger", "message", "traceId", "line");
    private final List<Part> parts;
    private final boolean needsFields;
    private final boolean wrapsLine;

    private LogLayout(List<Part> parts) {
        this.parts = List.copyOf(parts);
        this.needsFields = parts.stream().anyMatch(p -> p.names != null && p.names.stream().anyMatch(n -> !VIEW_NAMES.contains(n)
                && !n.equals("time") && !n.equals("stack")));
        this.wrapsLine = parts.stream().anyMatch(p -> p.names != null && p.names.contains("line"));
    }

    /**
     * Throws an argument error that says what is wrong with the template.
     */
    public static LogLayout compile(String template) {
        if (template == null || template.isEmpty() || template.length() > MAX_TEMPLATE_CHARS
                || template.chars().anyMatch(c -> c != '\t' && Character.isISOControl(c)))
            throw Errors.invalid("A line template is up to " + MAX_TEMPLATE_CHARS + " characters on one line, e.g. "
                    + "\"{time} {level:5} [{thread}] {logger} : {message}{stack}\".");
        var parts = new ArrayList<Part>();
        var literal = new StringBuilder();
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if ((c == '{' || c == '}') && i + 1 < template.length() && template.charAt(i + 1) == c) {
                literal.append(c);
                i++;
            } else if (c == '{') {
                int close = template.indexOf('}', i);
                if (close < 0) throw unbalanced();
                if (!literal.isEmpty()) parts.add(Part.literal(literal.toString()));
                literal.setLength(0);
                parts.add(placeholder(template.substring(i + 1, close)));
                i = close;
            } else if (c == '}') throw unbalanced();
            else literal.append(c);
        }
        if (!literal.isEmpty()) parts.add(Part.literal(literal.toString()));
        if (parts.stream().allMatch(p -> p.names == null))
            throw Errors.invalid("A line template needs at least one placeholder such as {message}.");
        return new LogLayout(parts);
    }

    private static Part placeholder(String body) {
        var matcher = PLACEHOLDER.matcher(body);
        if (!matcher.matches())
            throw Errors.invalid("Placeholder {" + body + "} is not a name like {message}, {level:5} or {thread|process.thread.name}.");
        var names = List.of(matcher.group(1).split("\\|"));
        String spec = matcher.group(2);
        if (spec == null) return new Part(null, names, 0, null);
        if (names.contains("time")) {
            if (names.size() > 1) throw Errors.invalid("{time:...} takes a date-time pattern and no other names.");
            try {
                return new Part(null, names, 0, DateTimeFormatter.ofPattern(spec));
            } catch (IllegalArgumentException e) {
                throw Errors.invalid("{time:" + spec + "} is not a date-time pattern; e.g. {time:yyyy-MM-dd HH:mm:ss.SSS}.");
            }
        }
        if (!WIDTH.matcher(spec).matches())
            throw Errors.invalid("{" + body + "}: after ':' comes a width like 5 (pad on the left) or -40 (pad on the right).");
        return new Part(null, names, Integer.parseInt(spec), null);
    }

    private static LokiOperationException unbalanced() {
        return Errors.invalid("A line template has an unbalanced brace; write {{ or }} for a literal brace.");
    }

    private static String pad(String value, int width) {
        if (width == 0 || value.length() >= Math.abs(width)) return value;
        String spaces = " ".repeat(Math.abs(width) - value.length());
        return width > 0 ? spaces + value : value + spaces;
    }

    /**
     * One event as text, without the trailing newline.
     */
    public String render(LogEvent event, EventNormalizer.View view, EventNormalizer normalizer, ZoneId zone) {
        Map<String, String> fields = null;
        boolean plain = view.format() == EventNormalizer.Format.PLAIN && !wrapsLine;
        if (needsFields || plain) {
            fields = new LinkedHashMap<>();
            normalizer.parse(event.line(), fields, new LinkedHashMap<>());
            if (plain && !fields.containsKey("message")) return event.line();
        }
        var text = new StringBuilder();
        for (var part : parts) {
            if (part.names == null) {
                text.append(part.literal);
                continue;
            }
            String value = "";
            for (String name : part.names) {
                String found = value(name, part, event, view, fields, zone);
                if (found != null && !found.isEmpty()) {
                    value = found;
                    break;
                }
            }
            text.append(pad(value, part.width));
        }
        return text.toString();
    }

    private static String value(String name, Part part, LogEvent event, EventNormalizer.View view, Map<String, String> fields, ZoneId zone) {
        return switch (name) {
            case "time" -> (part.time == null ? DEFAULT_TIME : part.time).format(QueryTime.fromNanos(event.timestampNanos()).atZone(zone));
            case "level" -> view.level();
            case "service" -> view.service();
            case "logger" -> view.logger();
            // The normalizer names an empty message for the summaries; a file keeps it empty, as it was logged.
            case "message" -> view.message() != null && view.message().startsWith(EventNormalizer.EMPTY_MESSAGE) ? "" : view.message();
            case "traceId" -> view.traceId();
            case "line" -> event.line();
            case "stack" -> view.stackTrace() == null || view.stackTrace().isBlank() ? null : "\n" + view.stackTrace().stripTrailing();
            default -> {
                String found = fields == null ? null : fields.get(name);
                if (found == null) found = event.labels().get(name);
                yield found == null ? event.structuredMetadata().get(name) : found;
            }
        };
    }

    private record Part(String literal, List<String> names, int width, DateTimeFormatter time) {
        static Part literal(String text) {
            return new Part(text, null, 0, null);
        }
    }
}
