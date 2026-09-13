package ru.it_spectrum.ai.loki.mcp.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.loki.mcp.model.QueryResults.*;
import static org.junit.jupiter.api.Assertions.*;

class ResponseProjectionTest {
    static Logs logs(List<Event> events) {
        return new Logs("test", new Window("1", "2"), "forward", 100, events.size(), events.size(), 2,
                false, Completeness.COMPLETE, "CURSORS_NOT_IMPLEMENTED", 500L, List.of(), events);
    }
    @Test void dictionaryPreservesTiesMultiplicityAndExactLabels() {
        var first = new Event("1", Map.of("job", "a"), "same", Map.of());
        var second = new Event("1", Map.of("job", "b"), "same", Map.of());
        var result = ResponseProjection.project(logs(List.of(first, second, first)), List.of());
        assertEquals(3, result.returnedEntries());
        assertEquals(2, result.streams().size());
        assertEquals(List.of("s0", "s1", "s0"), result.events().stream().map(e -> e.streamId()).toList());
        assertNull(result.events().getFirst().line());
        assertEquals(Map.of("job", "a"), result.streams().getFirst().resultLabels());
    }
    @Test void longUnicodeLineAndNormalizedStackAreMarkedWithoutSplittingCodePoints() {
        String stack = "Exception: ошибка\n" + "🐈\n\tat frame\"\\".repeat(2000) + "Caused by: root";
        String line = new tools.jackson.databind.json.JsonMapper().writeValueAsString(Map.of("error.stack_trace", stack));
        var source = logs(List.of(new Event("1", Map.of(), line, Map.of("trace", "id"))));
        var result = ResponseProjection.project(source, List.of("line", "normalized", "structuredMetadata"));
        var event = result.events().getFirst();
        assertTrue(event.line().contains("[truncated]"));
        assertTrue(event.line().endsWith("root\"}"));
        assertEquals(List.of("line", "normalized:/error.stack_trace"), event.truncatedFields());
        assertEquals("LINE_JSON", event.normalized().getFirst().origin().name());
        assertEquals(Map.of("trace", "id"), event.structuredMetadata());
        for (String text : List.of(event.line(), event.normalized().getFirst().value())) {
            for (int i = 0; i < text.length(); i++) if (Character.isSurrogate(text.charAt(i))) {
                assertTrue(Character.isHighSurrogate(text.charAt(i)));
                assertTrue(Character.isLowSurrogate(text.charAt(++i)));
            }
        }
        assertEquals(line, source.events().getFirst().line());
        assertEquals(Completeness.COMPLETE, result.completeness()); // All events returned, fields shortened separately.
    }
    @Test void fieldsAreExplicitAndValidated() {
        assertEquals(List.of("line"), ResponseProjection.fields(null));
        assertEquals(List.of(), ResponseProjection.fields(List.of()));
        for (var fields : List.of(List.of("unknown"), List.of("line", "line"), Arrays.asList((String) null))) {
            assertThrows(LokiOperationException.class, () -> ResponseProjection.fields(fields));
        }
    }
}
