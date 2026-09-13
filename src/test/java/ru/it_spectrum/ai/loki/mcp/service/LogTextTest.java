package ru.it_spectrum.ai.loki.mcp.service;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionDefinition;
import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;
import ru.it_spectrum.ai.loki.mcp.model.LogEvent;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LogTextTest {
    private final ZoneId zone = ZoneId.of("Europe/Moscow");
    private final EventNormalizer normalizer = new EventNormalizer();

    private LogEvent event(String line) {
        return new LogEvent(QueryTime.nanos(Instant.parse("2026-09-13T07:12:03.123456789Z")), Map.of("app", "backend", "level", "error"), line, Map.of());
    }

    private String line(LogEvent event, boolean raw) {
        return LogText.line(event, normalizer.view(event, ConnectionDefinition.DEFAULT_SERVICE_LABELS), zone, raw);
    }

    @Test
    void lineShowsLocalTimeLevelServiceMessageAndTrace() {
        var event = event("{\"message\":\"Connection refused\",\"traceId\":\"4f2a1b3c4d5e6f708192a3b4\"}");
        assertEquals("10:12:03.123 ERROR backend  Connection refused [trace=4f2a1b3c4d5e6f70…]", line(event, false));
        assertEquals("10:12:03.123 {app=\"backend\", level=\"error\"}  " + event.line(), line(event, true));
        var plain = new LogEvent(event.timestampNanos(), Map.of(), "just text", Map.of());
        assertEquals("10:12:03.123 -     -  just text", LogText.line(plain, normalizer.view(plain, List.of("app")), zone, false));
    }

    @Test
    void stackTraceIsCompactedToHeadersFirstFramesAndCauses() {
        var frames = new StringBuilder("java.lang.IllegalStateException: outer\n");
        for (int i = 0; i < 12; i++) frames.append("\tat a.b.C.m").append(i).append("(C.java:").append(i).append(")\n");
        frames.append("Caused by: java.io.IOException: inner\n\tat x.Y.z(Y.java:9)\n\tat x.Y.w(Y.java:8)\n\t... 40 more\n");
        var event = event("{\"message\":\"Boom\",\"error\":{\"stack_trace\":\"" + frames.toString().replace("\n", "\\n").replace("\t", "\\t") + "\"}}");
        var text = line(event, false);
        var lines = text.split("\n");
        assertEquals("10:12:03.123 ERROR backend  Boom", lines[0]);
        assertEquals("    java.lang.IllegalStateException: outer", lines[1]);
        assertEquals("    at a.b.C.m4(C.java:4)", lines[6]);
        assertEquals("    ... (7 frames skipped)", lines[7]);
        assertEquals("    Caused by: java.io.IOException: inner", lines[8]);
        assertEquals("    at x.Y.z(Y.java:9)", lines[9]);
        assertEquals("    ... (1 frames skipped)", lines[10]);
        assertEquals(11, lines.length);
    }

    @Test
    void longMessagesAreTruncatedOnCodePointsAndNewlinesFlattened() {
        String cats = "🐈".repeat(LogText.MESSAGE_CHARS + 5);
        var text = line(event("{\"message\":\"" + cats + "\"}"), false);
        assertEquals("10:12:03.123 ERROR backend  " + "🐈".repeat(LogText.MESSAGE_CHARS) + "…", text);
        assertEquals("10:12:03.123 ERROR backend  a b", line(event("{\"message\":\"a\\nb\"}"), false));
        assertTrue(line(event("x".repeat(LogText.RAW_CHARS + 1)), true).endsWith("…"));
    }

    @Test
    void fitDropsOldestLinesFirstAndFailsOnlyWhenNothingFits() {
        var lines = List.of("old", "middle", "new");
        assertEquals("h\nold\nmiddle\nnew\nf0", LogText.fit("h", lines, d -> "f" + d, 1000));
        assertEquals("h\nnew\nf2", LogText.fit("h", lines, d -> "f" + d, 9));
        var failure = assertThrows(LokiOperationException.class, () -> LogText.fit("header-too-long", lines, d -> "", 5));
        assertEquals(ErrorCode.RESPONSE_BUDGET_EXCEEDED, failure.error().code());
    }

    @Test
    void dateMarkersAppearOnlyWhenTheDayChanges() {
        var a = new LogEvent(QueryTime.nanos(Instant.parse("2026-09-13T20:59:59Z")), Map.of(), "a", Map.of());
        var b = new LogEvent(QueryTime.nanos(Instant.parse("2026-09-13T21:00:01Z")), Map.of(), "b", Map.of());
        assertEquals(List.of("A", "--- 2026-09-14 ---", "B"), LogText.withDateMarkers(List.of(a, b), List.of("A", "B"), zone));
        assertEquals(List.of("A", "B"), LogText.withDateMarkers(List.of(a, a), List.of("A", "B"), zone));
        assertEquals("2026-09-13 23:59:59–2026-09-14 00:00:01 (+03:00)",
                LogText.window(new QueryTime.Range(Instant.parse("2026-09-13T20:59:59Z"), Instant.parse("2026-09-13T21:00:01Z")), zone));
        assertEquals("2026-09-13 10:00:00–11:00:00 (+03:00)",
                LogText.window(new QueryTime.Range(Instant.parse("2026-09-13T07:00:00Z"), Instant.parse("2026-09-13T08:00:00Z")), zone));
    }
}
