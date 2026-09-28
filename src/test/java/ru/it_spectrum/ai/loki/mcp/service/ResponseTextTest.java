package ru.it_spectrum.ai.loki.mcp.service;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.loki.mcp.error.ErrorCode;
import ru.it_spectrum.ai.loki.mcp.error.LokiOperationException;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResponseTextTest {
    @Test
    void fitDropsOldestLinesFirstAndFailsOnlyWhenNothingFits() {
        var lines = List.of("old", "middle", "new");
        assertEquals("h\nold\nmiddle\nnew\nf0", ResponseText.fit("h", lines, d -> "f" + d, 1000));
        assertEquals("h\nnew\nf2", ResponseText.fit("h", lines, d -> "f" + d, 9));
        var failure = assertThrows(LokiOperationException.class,
                () -> ResponseText.fit("header-too-long", lines, d -> "", 5));
        assertEquals(ErrorCode.RESPONSE_BUDGET_EXCEEDED, failure.error().code());
    }

    @Test
    void windowUsesTheConnectionTimezoneAndNamesDayChanges() {
        var zone = ZoneId.of("Europe/Moscow");
        assertEquals("2026-09-13 23:59:59–2026-09-14 00:00:01 (+03:00)",
                ResponseText.window(new QueryTime.Range(Instant.parse("2026-09-13T20:59:59Z"),
                        Instant.parse("2026-09-13T21:00:01Z")), zone));
        assertEquals("2026-09-13 10:00:00–11:00:00 (+03:00)",
                ResponseText.window(new QueryTime.Range(Instant.parse("2026-09-13T07:00:00Z"),
                        Instant.parse("2026-09-13T08:00:00Z")), zone));
    }
}
