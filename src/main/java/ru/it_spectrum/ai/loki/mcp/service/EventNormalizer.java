package ru.it_spectrum.ai.loki.mcp.service;

import ru.it_spectrum.ai.loki.mcp.connection.LineFormat;
import ru.it_spectrum.ai.loki.mcp.model.LogEvent;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static ru.it_spectrum.ai.loki.mcp.service.DiscoveryLimits.*;

/**
 * Picks level, service, logger, message, trace id and stack trace out of labels, structured metadata and a JSON line,
 * or a plain-text line that one of the connection's line formats splits into fields. Only the line is parsed; nothing
 * is guessed when a value is absent, and no layout is known to the code.
 */
public final class EventNormalizer {
    static final List<String> LEVEL_LABELS = List.of("level", "detected_level", "severity", "lvl");
    static final List<String> LEVEL_FIELDS = List.of("log.level", "level", "severity", "lvl", "@l");
    static final List<String> SERVICE_FIELDS = List.of("service.name", "service", "app", "application", "applicationName");
    static final List<String> LOGGER_FIELDS = List.of("log.logger", "logger_name", "logger", "log.logger_name");
    static final List<String> MESSAGE_FIELDS = List.of("message", "msg", "@message", "event", "@m");
    static final List<String> TRACE_KEYS = List.of("traceId", "trace.id", "trace_id", "traceID", "trace");
    static final List<String> STACK_FIELDS = List.of("error.stack_trace", "stack_trace", "stacktrace", "stackTrace", "exception", "throwable");
    static final String EMPTY_MESSAGE = "(empty message";
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Pattern PLAIN_LEVEL = Pattern.compile("\\b(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL)\\b");
    private final List<LineFormat> formats;
    public EventNormalizer() {
        this(List.of());
    }
    public EventNormalizer(List<LineFormat> formats) {
        this.formats = List.copyOf(formats);
    }

    private static void visit(JsonNode node, String path, int depth, Map<String, String> values, Map<String, String> types) {
        for (var property : node.properties()) {
            if (types.size() >= FIELDS) return;
            String name = path.isEmpty() ? property.getKey() : path + "." + property.getKey();
            JsonNode value = property.getValue();
            if (value.isObject()) {
                if (depth + 1 < JSON_DEPTH && !value.isEmpty()) visit(value, name, depth + 1, values, types);
                else types.put(name, "object");
            } else {
                types.put(name, value.isArray() ? "array" : value.isString() ? "string" : value.isNumber() ? "number"
                                                                                          : value.isBoolean() ? "boolean" : "null");
                if (value.isValueNode() && !value.isNull()) values.put(name, value.asString());
            }
        }
    }

    static String first(Map<String, String> source, List<String> keys) {
        for (String key : keys) {
            String value = source.get(key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    /**
     * Field name as Loki's json parser exposes it after {@code | json}: nested keys joined and sanitized with underscores.
     */
    public static String lokiFieldName(String dottedPath) {
        String name = dottedPath.replaceAll("[^a-zA-Z0-9_]", "_");
        return Character.isDigit(name.charAt(0)) ? "_" + name : name;
    }

    public View view(LogEvent event, List<String> serviceLabels) {
        var values = new LinkedHashMap<String, String>();
        var types = new LinkedHashMap<String, String>();
        Format format = parse(event.line(), values, types);
        String level = first(event.labels(), LEVEL_LABELS);
        // Loki 3.x stamps detected_level="unknown" when it finds nothing; that is the absence of a level, not a level.
        if (level == null) level = first(event.structuredMetadata(), LEVEL_LABELS);
        if (level != null && level.equalsIgnoreCase("unknown")) level = null;
        if (level == null) level = first(values, LEVEL_FIELDS);
        if (level == null && format == Format.PLAIN) {
            var matcher = PLAIN_LEVEL.matcher(event.line().substring(0, Math.min(event.line().length(), 120)));
            if (matcher.find()) level = matcher.group(1);
        }
        String service = first(event.labels(), serviceLabels);
        if (service == null) service = first(values, SERVICE_FIELDS);
        String logger = first(values, LOGGER_FIELDS);
        String trace = first(event.structuredMetadata(), TRACE_KEYS);
        if (trace == null) trace = first(values, TRACE_KEYS);
        if (trace == null) trace = first(event.labels(), TRACE_KEYS);
        String message, stack = null;
        if (format == Format.JSON) {
            message = first(values, MESSAGE_FIELDS);
            if (message == null) message = event.line();
            stack = first(values, STACK_FIELDS);
        } else {
            String line = event.line();
            int newline = line.indexOf('\n');
            // Multi-line plain text: the first line is the message; the rest is a trace when it holds frames.
            if (newline >= 0 && (line.contains("\n\tat ") || line.contains("\n    at ") || line.contains("\nat "))) {
                message = line.substring(0, newline);
                stack = line.substring(newline + 1);
            } else message = line;
            // A line format replaces the first line with the message it found there; the rest of the line stays. An
            // empty message is the rest of the line (a report that starts on the next line), else it says so: a
            // shipper that sends every line apart leaves the report in the next entries, which getLogContext shows.
            String found = values.get("message");
            if (found != null && !found.isBlank())
                message = newline >= 0 && stack == null ? found + line.substring(newline) : found;
            else if (found != null && newline >= 0 && !line.substring(newline).isBlank())
                message = stack == null ? line.substring(newline + 1).strip() : message;
            else if (found != null)
                message = logger == null ? EMPTY_MESSAGE + ")" : EMPTY_MESSAGE + ", logger " + logger + ")";
        }
        return new View(format, level == null ? null : level.toUpperCase(Locale.ROOT), service, logger, message, trace, stack, types);
    }

    /**
     * Fills dotted field paths with values (scalars) and JSON types. Returns PLAIN when the line is not a JSON object;
     * the named groups of the first line format that finds a match in its first line are its values then (no types).
     */
    public Format parse(String line, Map<String, String> values, Map<String, String> types) {
        if (line.isEmpty() || line.charAt(0) != '{') {
            plain(line, values);
            return Format.PLAIN;
        }
        if (line.length() > PARSE_CHARACTERS) return Format.PLAIN;
        JsonNode root;
        try {
            root = MAPPER.readTree(line);
        } catch (Exception ignored) {
            return Format.PLAIN;
        }
        if (root == null || !root.isObject()) return Format.PLAIN;
        visit(root, "", 0, values, types);
        return Format.JSON;
    }

    private void plain(String line, Map<String, String> values) {
        if (formats.isEmpty()) return;
        int newline = line.indexOf('\n');
        String first = newline < 0 ? line : line.substring(0, newline);
        if (first.length() > PARSE_CHARACTERS) first = first.substring(0, PARSE_CHARACTERS);
        for (var format : formats) {
            var matcher = format.pattern().matcher(first);
            if (!matcher.find()) continue;
            for (String name : format.pattern().namedGroups().keySet()) {
                String value = matcher.group(name);
                // The message is kept even when empty: it tells that the format matched.
                if (name.equals("message")) values.put(name, value == null ? "" : value);
                else if (value != null && !value.isBlank()) values.put(name, value.strip());
            }
            return;
        }
    }

    public enum Format {JSON, PLAIN}

    public record View(Format format, String level, String service, String logger, String message, String traceId,
                       String stackTrace, Map<String, String> jsonFields) {
        public View {
            jsonFields = Map.copyOf(jsonFields);
        }
    }
}
