package ru.it_spectrum.ai.loki.mcp.service;

import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.loki.mcp.client.*;
import ru.it_spectrum.ai.loki.mcp.connection.*;
import static ru.it_spectrum.ai.loki.mcp.client.LokiResponses.*;
import static ru.it_spectrum.ai.loki.mcp.model.QueryResults.Completeness.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class QueryServiceTest {
    private final LokiHttpClient client = mock(LokiHttpClient.class);
    private final Instant now = Instant.parse("2026-09-13T12:00:00.123456789Z");
    private final ConnectionRegistry registry = new ConnectionRegistry(List.of(
            new ConnectionDefinition("one", null, URI.create("http://localhost:1"), ConnectionAuth.NONE, null,
                    ZoneId.of("UTC"), new ConnectionLimits(100, 100, 10000, 1024, 3, 900, 2, 5)),
            new ConnectionDefinition("two", null, URI.create("http://localhost:2"), ConnectionAuth.NONE, null,
                    ZoneId.of("UTC"), new ConnectionLimits(100, 100, 10000, 1024, 1, 10, 1, 1))));
    private final QueryService service = new QueryService(registry, client, Clock.fixed(now, ZoneOffset.UTC));
    private void range(QueryData data, List<String> warnings) {
        when(client.queryRange(anyString(), anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new QueryResponse(data, new QueryStats(999L), warnings));
    }
    @Test void globallySortsWithoutLosingSameTimestampDuplicatesAndKeepsNanoseconds() {
        String a = QueryTime.nanos(now.minusNanos(1)), b = QueryTime.nanos(now);
        range(new Streams(List.of(new LogStream(Map.of("a", "one"), List.of(new LogEntry(b, "Ошибка 🐈", Map.of()))),
                new LogStream(Map.of("a", "two"), List.of(new LogEntry(a, "dup", Map.of("trace", "x")),
                        new LogEntry(a, "dup", Map.of("trace", "x")))))), List.of());
        var result = service.logs("one", "{a=~\".+\"} | line_format `{{.message}}`", "now-1s", "now", "forward", 3);
        assertEquals(List.of(a, a, b), result.events().stream().map(e -> e.timestampNanos()).toList());
        assertEquals(3, result.returnedEntries());
        assertEquals(2, result.resultStreams());
        assertEquals(999L, result.totalLinesProcessed());
        assertEquals(UNKNOWN, result.completeness());
        assertEquals("x", result.events().getFirst().structuredMetadata().get("trace"));
        verify(client).queryRange(eq("one"), eq("{a=~\".+\"} | line_format `{{.message}}`"),
                eq(now.minusSeconds(1)), eq(now), eq(3), eq(LokiHttpClient.Direction.FORWARD), isNull());
        var reverse = service.logs("one", "q", "now-1s", "now", null, 2);
        assertEquals(b, reverse.events().getFirst().timestampNanos());
        assertEquals(PARTIAL, reverse.completeness());
    }
    @Test void emptyAndWarningsAreNotInventedMatches() {
        range(new Streams(List.of()), List.of());
        var empty = service.logs("one", "q", "now-1s", "now", null, null);
        assertEquals(COMPLETE, empty.completeness());
        assertEquals(0, empty.readEntries());
        range(new Streams(List.of()), List.of("SECRET_URL_TOKEN"));
        var warning = service.logs("one", "q", "now-1s", "now", null, null);
        assertEquals(UNKNOWN, warning.completeness());
        assertFalse(warning.toString().contains("SECRET"));
    }
    @Test void validatesBeforeNetworkAndUsesConnectionSpecificCaps() {
        assertThrows(LokiOperationException.class, () -> service.logs(null, "q", "now-1s", "now", null, null));
        assertThrows(LokiOperationException.class, () -> service.logs("missing", "q", "now-1s", "now", null, null));
        assertThrows(LokiOperationException.class, () -> service.logs("two", "q", "now-11s", "now", null, null));
        assertThrows(LokiOperationException.class, () -> service.logs("two", "q", "now-1s", "now", null, 2));
        assertThrows(LokiOperationException.class, () -> service.logs("one", "q", "now-1s", "now", "wrong", 1));
        assertThrows(LokiOperationException.class, () -> service.logs("one", " ", "now-1s", "now", null, 1));
        verifyNoInteractions(client);
    }
    @Test void metricLimitsCountSeriesAndPointsSeparately() {
        var samples = List.of(new MetricSample(new BigDecimal("1700000000.123456789"), "NaN"),
                new MetricSample(new BigDecimal("1700000001.123456789"), "+Inf"), new MetricSample(BigDecimal.TEN, "-Inf"));
        range(new Matrix(List.of(new MetricSeries(Map.of("a", "1"), samples), new MetricSeries(Map.of("a", "2"), samples),
                new MetricSeries(Map.of("a", "3"), samples))), List.of());
        var result = service.metrics("one", "q", "range", "now-2s", "now", null, BigDecimal.ONE, null, null);
        assertEquals(3, result.readSeries()); assertEquals(9, result.readPoints());
        assertEquals(2, result.returnedSeries()); assertEquals(5, result.returnedPoints());
        assertEquals(PARTIAL, result.completeness());
        assertEquals(samples.getFirst().timestampSeconds(), result.series().getFirst().samples().getFirst().timestampSeconds());
        assertEquals("NaN", result.series().getFirst().samples().getFirst().value());
    }
    @Test void instantAndEmptyMetricsAndWrongType() {
        when(client.queryInstant("one", "q", now)).thenReturn(new QueryResponse(new Vector(List.of()), new QueryStats(null), List.of()));
        var result = service.metrics("one", "q", "instant", null, null, "now", null, null, null);
        assertEquals(COMPLETE, result.completeness()); assertNull(result.stepSeconds()); assertNull(result.totalLinesProcessed());
        assertEquals(result.window().startNanos(), result.window().endNanos());
        range(new Matrix(List.of()), List.of());
        assertEquals(COMPLETE, service.metrics("one", "q", "range", "now-1s", "now", null, BigDecimal.ONE, null, null).completeness());
        assertThrows(LokiOperationException.class, () -> service.logs("one", "q", "now-1s", "now", null, null));
        range(new Streams(List.of()), List.of());
        assertThrows(LokiOperationException.class, () -> service.metrics("one", "q", "range", "now-1s", "now", null, BigDecimal.ONE, null, null));
    }
    @Test void rejectsConflictingMetricArgumentsAndExcessiveEvaluations() {
        assertThrows(LokiOperationException.class, () -> service.metrics("one", "q", "instant", "now-1s", null, "now", null, null, null));
        assertThrows(LokiOperationException.class, () -> service.metrics("one", "q", "instant", null, null, null, null, null, null));
        assertThrows(LokiOperationException.class, () -> service.metrics("one", "q", "range", "now-5s", "now", null, BigDecimal.ONE, null, null));
        assertThrows(LokiOperationException.class, () -> service.metrics("one", "q", "range", "now-1s", "now", null, BigDecimal.ZERO, null, null));
        assertThrows(LokiOperationException.class, () -> service.metrics("one", "q", "range", "now-1s", "now", "now", BigDecimal.ONE, null, null));
        assertThrows(LokiOperationException.class, () -> service.metrics("one", "q", "range", "now-1s", "now", null, null, null, null));
        assertThrows(LokiOperationException.class, () -> service.metrics("one", "q", "other", null, null, "now", null, null, null));
        assertThrows(LokiOperationException.class, () -> service.metrics("two", "q", "instant", null, null, "now", null, 2, null));
        verifyNoInteractions(client);
    }
}
