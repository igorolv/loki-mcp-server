package ru.it_spectrum.ai.loki.mcp.parser;

import ru.it_spectrum.ai.loki.mcp.service.Errors;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A JSON shape and its preferred paths for the normalized line fields. */
public record JsonFormat(String id, List<String> requiredFields, Map<String, String> fieldEquals,
                         Map<String, List<String>> fields) {
    private static final Set<String> VIEW_FIELDS = Set.of("level", "service", "logger", "message", "traceId", "stack");

    public JsonFormat {
        if (id == null || !id.matches("[a-z0-9][a-z0-9-]{0,63}") || requiredFields == null
                || fieldEquals == null || fields == null || requiredFields.size() > 16 || fieldEquals.size() > 16
                || requiredFields.isEmpty() && fieldEquals.isEmpty()
                || requiredFields.stream().anyMatch(path -> !validPath(path))
                || fieldEquals.entrySet().stream().anyMatch(entry -> !validPath(entry.getKey())
                || entry.getValue() == null || entry.getValue().isBlank() || entry.getValue().length() > 200)
                || fields.entrySet().stream().anyMatch(entry -> !VIEW_FIELDS.contains(entry.getKey())
                || entry.getValue() == null || entry.getValue().isEmpty() || entry.getValue().size() > 16
                || entry.getValue().stream().anyMatch(path -> !validPath(path)))) {
            throw Errors.configuration();
        }
        requiredFields = List.copyOf(requiredFields);
        fieldEquals = Map.copyOf(fieldEquals);
        var copiedFields = new LinkedHashMap<String, List<String>>();
        fields.forEach((name, paths) -> copiedFields.put(name, List.copyOf(paths)));
        fields = Map.copyOf(copiedFields);
    }

    private static boolean validPath(String path) {
        return path != null && !path.isBlank() && path.length() <= 200
                && path.chars().noneMatch(Character::isISOControl);
    }

    public boolean matches(Map<String, String> values) {
        for (String path : requiredFields) {
            String value = values.get(path);
            if (value == null || value.isBlank()) return false;
        }
        for (var entry : fieldEquals.entrySet()) {
            if (!entry.getValue().equals(values.get(entry.getKey()))) return false;
        }
        return true;
    }

    public String field(Map<String, String> values, String name) {
        List<String> paths = fields.get(name);
        return paths == null ? null : EventNormalizer.first(values, paths);
    }

    @Override
    public String toString() {
        return "JsonFormat[" + id + "]";
    }
}
