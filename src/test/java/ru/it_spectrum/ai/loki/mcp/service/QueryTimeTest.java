package ru.it_spectrum.ai.loki.mcp.service;

import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class QueryTimeTest {
    private final Instant now = Instant.parse("2026-09-13T12:00:00.123456789Z");
    private final ZoneId zone = ZoneId.of("Europe/Berlin");
    @Test void exactTimeAndSingleAnchor() {
        var window = QueryTime.range("now-15m", "now", now, zone, 900);
        assertEquals(now.minusSeconds(900), window.start());
        assertEquals(now, window.end());
        assertEquals(now, QueryTime.parse(QueryTime.nanos(now), now, zone));
        assertEquals(now, QueryTime.parse("2026-09-13T14:00:00.123456789+02:00", now, zone));
        assertEquals(now, QueryTime.parse("2026-09-13T14:00:00.123456789", now, zone));
        assertEquals(Instant.ofEpochSecond(-1, 999999999), QueryTime.parse("-1", now, zone));
        assertEquals(now.minusNanos(1), QueryTime.parse("now-1ns", now, zone));
    }
    @ParameterizedTest @ValueSource(strings = {"", "secret", "now-0m", "now+1h", "9223372036854775808",
            "now-999999999999999999999d", "2026-03-29T02:30:00", "2026-10-25T02:30:00", "9999-01-01T00:00:00Z"})
    void rejectsInvalidAndAmbiguousTimeWithoutEcho(String value) {
        var failure = assertThrows(LokiOperationException.class, () -> QueryTime.parse(value, now, zone));
        assertFalse(failure.getMessage().contains("secret"));
    }
    @Test void rejectsMissingReversedAndTooWideIntervals() {
        assertThrows(LokiOperationException.class, () -> QueryTime.parse(null, now, zone));
        assertThrows(LokiOperationException.class, () -> QueryTime.range("now", "now", now, zone, 900));
        assertThrows(LokiOperationException.class, () -> QueryTime.range("now", "now-1s", now, zone, 900));
        assertThrows(LokiOperationException.class, () -> QueryTime.range("now-901s", "now", now, zone, 900));
    }
}
