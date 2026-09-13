package ru.it_spectrum.ai.loki.mcp.service;

import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ru.it_spectrum.ai.loki.mcp.model.LogEvent;
import static ru.it_spectrum.ai.loki.mcp.service.DiscoveryLimits.*;

/** Picks level, service, message, trace id and stack trace out of labels, structured metadata and a JSON line.
 * Only the line is parsed; nothing is guessed when a value is absent. */
public final class EventNormalizer {
    public enum Format { JSON, PLAIN }
    public record View(Format format, String level, String service, String message, String traceId, String stackTrace,
                       Map<String, String> jsonFields) {
        public View { jsonFields = Map.copyOf(jsonFields); }
    }
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final List<String> LEVEL_LABELS = List.of("level", "detected_level", "severity", "lvl");
    private static final List<String> LEVEL_FIELDS = List.of("log.level", "level", "severity", "lvl", "@l");
    private static final List<String> SERVICE_FIELDS = List.of("service.name", "service", "app", "application", "applicationName");
    private static final List<String> MESSAGE_FIELDS = List.of("message", "msg", "@message", "event", "@m");
    private static final List<String> TRACE_KEYS = List.of("traceId", "trace.id", "trace_id", "traceID", "trace");
    private static final List<String> STACK_FIELDS = List.of("error.stack_trace", "stack_trace", "stacktrace", "stackTrace", "exception", "throwable");
    private static final Pattern PLAIN_LEVEL = Pattern.compile("\\b(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL)\\b");

    public View view(LogEvent event, List<String> serviceLabels) {
        var values = new LinkedHashMap<String, String>();
        var types = new LinkedHashMap<String, String>();
        Format format = parse(event.line(), values, types);
        String level = first(event.labels(), LEVEL_LABELS);
        if (level == null) level = first(event.structuredMetadata(), LEVEL_LABELS);
        if (level == null) level = first(values, LEVEL_FIELDS);
        if (level == null && format == Format.PLAIN) {
            var matcher = PLAIN_LEVEL.matcher(event.line().substring(0, Math.min(event.line().length(), 120)));
            if (matcher.find()) level = matcher.group(1);
        }
        String service = first(event.labels(), serviceLabels);
        if (service == null) service = first(values, SERVICE_FIELDS);
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
        }
        return new View(format, level == null ? null : level.toUpperCase(Locale.ROOT), service, message, trace, stack, types);
    }

    /** Fills dotted field paths with values (scalars) and JSON types. Returns PLAIN when the line is not a JSON object. */
    public Format parse(String line, Map<String, String> values, Map<String, String> types) {
        if (line.length() > PARSE_CHARACTERS || line.isEmpty() || line.charAt(0) != '{') return Format.PLAIN;
        JsonNode root;
        try { root = MAPPER.readTree(line); } catch (Exception ignored) { return Format.PLAIN; }
        if (root == null || !root.isObject()) return Format.PLAIN;
        visit(root, "", 0, values, types);
        return Format.JSON;
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

    private static String first(Map<String, String> source, List<String> keys) {
        for (String key : keys) {
            String value = source.get(key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    /** Field name as Loki's json parser exposes it after {@code | json}: nested keys joined and sanitized with underscores. */
    public static String lokiFieldName(String dottedPath) {
        String name = dottedPath.replaceAll("[^a-zA-Z0-9_]", "_");
        return Character.isDigit(name.charAt(0)) ? "_" + name : name;
    }
}
