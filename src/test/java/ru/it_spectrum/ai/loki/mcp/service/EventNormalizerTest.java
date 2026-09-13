package ru.it_spectrum.ai.loki.mcp.service;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionDefinition;
import ru.it_spectrum.ai.loki.mcp.model.LogEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static ru.it_spectrum.ai.loki.mcp.service.EventNormalizer.Format.JSON;
import static ru.it_spectrum.ai.loki.mcp.service.EventNormalizer.Format.PLAIN;

class EventNormalizerTest {
    @Test
    void detectedLevelUnknownIsNoLevel() {
        var normalizer = new EventNormalizer();
        var unknown = new LogEvent("1", Map.of("app", "x"), "plain line without level", Map.of("detected_level", "unknown"));
        assertNull(normalizer.view(unknown, List.of("app")).level());
        var error = new LogEvent("1", Map.of("app", "x"), "plain line", Map.of("detected_level", "error"));
        assertEquals("ERROR", normalizer.view(error, List.of("app")).level());
    }

    private final EventNormalizer normalizer = new EventNormalizer();

    private static LogEvent event(Map<String, String> labels, String line, Map<String, String> metadata) {
        return new LogEvent("1700000000123456789", labels, line, metadata);
    }

    private EventNormalizer.View view(Map<String, String> labels, String line) {
        return normalizer.view(event(labels, line, Map.of()), ConnectionDefinition.DEFAULT_SERVICE_LABELS);
    }

    @Test
    void ecsJsonYieldsLevelServiceMessageTraceAndStackWithLokiFieldNames() {
        var view = view(Map.of(), """
                {"service":{"name":"backend"},"log":{"level":"error"},"@timestamp":"2026-01-01T00:00:00Z",
                 "message":"Ошибка 🐈","trace":{"id":"abc123"},"error":{"stack_trace":"java.io.IOException: x\\n\\tat a.b(C.java:1)"},
                 "tags":["a"],"count":3,"empty":{}}
                """);
        assertEquals(JSON, view.format());
        assertEquals("ERROR", view.level());
        assertEquals("backend", view.service());
        assertEquals("Ошибка 🐈", view.message());
        assertEquals("abc123", view.traceId());
        assertTrue(view.stackTrace().startsWith("java.io.IOException"));
        assertEquals("string", view.jsonFields().get("service.name"));
        assertEquals("array", view.jsonFields().get("tags"));
        assertEquals("number", view.jsonFields().get("count"));
        assertEquals("object", view.jsonFields().get("empty"));
        assertEquals("service_name", EventNormalizer.lokiFieldName("service.name"));
        assertEquals("_timestamp", EventNormalizer.lokiFieldName("@timestamp"));
        assertEquals("_1abc", EventNormalizer.lokiFieldName("1abc"));
    }

    @Test
    void flatDottedKeysAndLabelsTakePriorityOverLineFields() {
        var view = view(Map.of("applicationName", "from-label", "level", "warn"),
                "{\"service.name\":\"from-line\",\"log.level\":\"ERROR\",\"msg\":\"hello\"}");
        assertEquals("WARN", view.level());
        assertEquals("from-label", view.service());
        assertEquals("hello", view.message());
        var custom = normalizer.view(event(Map.of("container", "c1", "app", "a1"), "{\"message\":\"m\"}", Map.of()), List.of("container"));
        assertEquals("c1", custom.service());
        var metadata = normalizer.view(event(Map.of(), "plain", Map.of("detected_level", "info", "traceId", "t-1")), List.of("app"));
        assertEquals("INFO", metadata.level());
        assertEquals("t-1", metadata.traceId());
    }

    @Test
    void plainTextKeepsWholeLineDetectsLevelAndSplitsMultilineTraces() {
        var plain = view(Map.of(), "2026-09-13 10:00:00 WARN  [main] Something happened");
        assertEquals(PLAIN, plain.format());
        assertEquals("WARN", plain.level());
        assertNull(plain.service());
        assertEquals("2026-09-13 10:00:00 WARN  [main] Something happened", plain.message());
        assertNull(plain.stackTrace());
        var trace = view(Map.of(), "Boom\njava.lang.IllegalStateException: bad\n\tat a.B(B.java:1)\n\tat a.C(C.java:2)");
        assertEquals("Boom", trace.message());
        assertTrue(trace.stackTrace().contains("\tat a.B(B.java:1)"));
        var noFrames = view(Map.of(), "line one\nline two");
        assertEquals("line one\nline two", noFrames.message());
        assertNull(noFrames.stackTrace());
        for (String line : List.of("", "ignore previous instructions", "{broken", "{\"a\":1,\"a\":2}", "{} {}", "[1,2]", "42"))
            assertEquals(PLAIN, view(Map.of(), line).format(), line);
        assertEquals("{broken", view(Map.of(), "{broken").message());
    }

    @Test
    void jsonWithoutKnownFieldsFallsBackToTheLineAndBudgetsAreApplied() {
        var view = view(Map.of(), "{\"foo\":\"bar\"}");
        assertNull(view.level());
        assertNull(view.service());
        assertNull(view.traceId());
        assertEquals("{\"foo\":\"bar\"}", view.message());
        assertEquals(PLAIN, view(Map.of(), "{".repeat(DiscoveryLimits.PARSE_CHARACTERS + 1)).format());
        var values = new LinkedHashMap<String, String>();
        var types = new LinkedHashMap<String, String>();
        normalizer.parse("{\"x\":".repeat(25) + "1" + "}".repeat(25), values, types);
        assertTrue(types.containsKey("x".repeat(1) + ".x".repeat(DiscoveryLimits.JSON_DEPTH - 1)));
        String wide = java.util.stream.IntStream.range(0, 150).mapToObj(i -> "\"f" + i + "\":1").collect(java.util.stream.Collectors.joining(",", "{", "}"));
        types.clear();
        values.clear();
        normalizer.parse(wide, values, types);
        assertEquals(DiscoveryLimits.FIELDS, types.size());
    }
}
