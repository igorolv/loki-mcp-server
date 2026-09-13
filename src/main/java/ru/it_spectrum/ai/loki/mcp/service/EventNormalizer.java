package ru.it_spectrum.ai.loki.mcp.service;

import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ru.it_spectrum.ai.loki.mcp.model.DiscoveryResult.*;
import static ru.it_spectrum.ai.loki.mcp.service.DiscoveryLimits.*;

/** Only the line is parsed. Labels/metadata never override its fields. Original text is retained by callers. */
public final class EventNormalizer {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Map<String, String> ECS = Map.ofEntries(
            Map.entry("/service.name", "service"), Map.entry("/service/name", "service"),
            Map.entry("/log.level", "level"), Map.entry("/log/level", "level"),
            Map.entry("/message", "message"), Map.entry("/@timestamp", "eventTime"),
            Map.entry("/trace.id", "traceId"), Map.entry("/trace/id", "traceId"),
            Map.entry("/error.stack_trace", "stackTrace"), Map.entry("/error/stack_trace", "stackTrace"));
    public record Parsed(Format format, Map<String, String> fields,
                         List<NormalizedValue> normalized, List<String> limitations) {
        public Parsed { fields = Map.copyOf(fields); normalized = List.copyOf(normalized); limitations = List.copyOf(limitations); }
    }
    public Parsed parse(String line) {
        if (line.length() > PARSE_CHARACTERS) return new Parsed(Format.UNPARSED, Map.of(), List.of(), List.of("LINE_PARSE_SIZE_LIMIT"));
        JsonNode root;
        try { root = MAPPER.readTree(line); }
        catch (Exception ignored) {
            return new Parsed(Format.PLAIN_TEXT, Map.of(), List.of(), List.of("NOT_STRICT_JSON_OR_PARSER_LIMIT"));
        }
        if (root == null || root.isMissingNode()) return new Parsed(Format.PLAIN_TEXT, Map.of(), List.of(), List.of());
        var fields = new LinkedHashMap<String, String>();
        var normalized = new ArrayList<NormalizedValue>();
        var limits = new LinkedHashSet<String>();
        visit(root, "", 0, fields, normalized, limits);
        return new Parsed(root.isObject() ? Format.JSON_OBJECT : Format.JSON_VALUE, fields, normalized, new ArrayList<>(limits));
    }
    private void visit(JsonNode node, String path, int depth, Map<String, String> fields,
                       List<NormalizedValue> normalized, Set<String> limits) {
        if (fields.size() >= FIELDS) { limits.add("LINE_FIELD_LIMIT"); return; }
        String type = node.isObject() ? "object" : node.isArray() ? "array" : node.isString() ? "string"
                : node.isNumber() ? "number" : node.isBoolean() ? "boolean" : "null";
        // Root object is a format, not a discovered field. Arrays are reported without indexing every element.
        if (!path.isEmpty() || !node.isObject()) fields.put(path, type);
        if (node.isString() && ECS.containsKey(path)) normalized.add(new NormalizedValue(ECS.get(path), Origin.LINE_JSON, path, node.stringValue()));
        if (!node.isObject()) return;
        if (depth >= JSON_DEPTH) { limits.add("LINE_DEPTH_LIMIT"); return; }
        for (var property : node.properties()) {
            if (fields.size() >= FIELDS) { limits.add("LINE_FIELD_LIMIT"); break; }
            visit(property.getValue(), path + "/" + pointer(property.getKey()), depth + 1, fields, normalized, limits);
        }
    }
    public static String pointer(String name) { return name.replace("~", "~0").replace("/", "~1"); }
}
