package ru.it_spectrum.ai.loki.mcp.service;

import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.loki.mcp.client.*;
import ru.it_spectrum.ai.loki.mcp.connection.*;
import ru.it_spectrum.ai.loki.mcp.model.*;
import ru.it_spectrum.ai.loki.mcp.model.DiscoveryResult.*;
import static ru.it_spectrum.ai.loki.mcp.client.LokiResponses.*;
import static ru.it_spectrum.ai.loki.mcp.model.QueryResults.Completeness.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DiscoveryServiceTest {
    private final LokiHttpClient client = mock(LokiHttpClient.class);
    private final Instant now = Instant.parse("2026-09-13T12:00:00.123456789Z");
    private final ConnectionRegistry registry = new ConnectionRegistry(List.of(
            new ConnectionDefinition("one", null, URI.create("http://localhost:1"), ConnectionAuth.NONE, null,
                    ZoneOffset.UTC, ConnectionLimits.DEFAULTS),
            new ConnectionDefinition("two", null, URI.create("http://localhost:2"), ConnectionAuth.NONE, null,
                    ZoneOffset.UTC, new ConnectionLimits(100, 100, 10000, 1024, 1, 10))));
    private final DiscoveryService service = new DiscoveryService(registry, client, Clock.fixed(now, ZoneOffset.UTC));
    private static final String SELECTOR = "{job=\"test\"}";
    private void series(List<Map<String, String>> streams) {
        when(client.series(anyString(), anyList(), any(), any())).thenReturn(new SeriesResponse(streams, List.of()));
    }
    private void entries(List<LogEntry> entries) {
        when(client.queryRange(anyString(), anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new QueryResponse(new Streams(List.of(new LogStream(Map.of("service.name", "result-value"), entries))), null, List.of()));
    }
    private LogEntry entry(String line) { return new LogEntry(QueryTime.nanos(now.minusNanos(1)), line, Map.of("service.name", "metadata-value")); }
    private DiscoveryResult discover() { return service.discover("one", SELECTOR, "now-1s", "now", null); }
    @Test void mixedFormatsCountsProvenanceMissingLabelsAndExactScope() {
        series(List.of(Map.of("job", "test")));
        entries(List.of(entry("{\"service.name\":\"backend\",\"message\":\"Ошибка 🐈\"}"), entry("plain"), entry("{\"message\":12}"), entry("plain")));
        var result = discover();
        assertEquals(List.of("job"), result.streamLabels().stream().map(Label::name).toList());
        assertEquals(4, result.coverage().entriesExamined()); assertEquals(COMPLETE, result.coverage().sampleCompleteness());
        assertEquals(3, result.examples().size());
        assertEquals(2, result.formats().stream().filter(f -> f.format() == Format.PLAIN_TEXT).findFirst().orElseThrow().entries());
        var field = result.fields().stream().filter(f -> f.path().equals("/message") && f.origin() == Origin.LINE_JSON).findFirst().orElseThrow();
        assertEquals(2, field.observedEntries()); assertEquals(List.of("number", "string"), field.types());
        assertEquals(3, result.fields().stream().filter(f -> f.path().equals("/service.name")).count());
        assertEquals("backend", result.examples().getFirst().normalized().stream().filter(v -> v.name().equals("service")).findFirst().orElseThrow().value());
        verify(client).series("one", List.of(SELECTOR), now.minusSeconds(1), now);
        verify(client).queryRange("one", SELECTOR, now.minusSeconds(1), now, 20, LokiHttpClient.Direction.BACKWARD, null);
        verifyNoMoreInteractions(client);
    }
    @Test void highCardinalityAndSeriesCapsAreVisibleAndIndependentFromSampleCompleteness() {
        series(java.util.stream.IntStream.range(0, 150).mapToObj(i -> Map.of("pod", "pod-" + i)).toList());
        entries(List.of());
        var result = discover();
        assertEquals(150, result.coverage().seriesRead()); assertEquals(100, result.coverage().seriesExamined());
        assertTrue(result.coverage().localTruncation());
        assertEquals(20, result.streamLabels().getFirst().observedValues().size());
        assertTrue(result.streamLabels().getFirst().valuesTruncated());
        assertEquals(COMPLETE, result.coverage().sampleCompleteness());
    }
    @Test void capsLabelsFieldsAndSamplesWithoutDeduplication() {
        var labels = new HashMap<String, String>();
        for (int i = 0; i < 40; i++) labels.put("label" + i, "v");
        series(List.of(labels));
        String wide = java.util.stream.IntStream.range(0, 110).mapToObj(i -> "\"f" + i + "\":1").collect(java.util.stream.Collectors.joining(",", "{", "}"));
        entries(Collections.nCopies(22, entry(wide)));
        var result = discover();
        assertEquals(30, result.streamLabels().size()); assertEquals(100, result.fields().size());
        assertEquals(22, result.coverage().entriesRead()); assertEquals(20, result.coverage().entriesExamined());
        assertEquals(PARTIAL, result.coverage().sampleCompleteness());
        assertTrue(result.coverage().localTruncation());
        assertEquals(20, result.fields().getFirst().observedEntries());
    }
    @Test void closedSeriesPathDoesNotBlockLogReadingOrLeakErrorsAndDoesNotPersistAcrossConnections() {
        when(client.series(eq("one"), anyList(), any(), any())).thenThrow(Errors.failure(ErrorCode.ENDPOINT_UNAVAILABLE, "SAFE"));
        when(client.series(eq("two"), anyList(), any(), any())).thenReturn(new SeriesResponse(List.of(), List.of()));
        entries(List.of(entry("plain")));
        var result = discover();
        assertEquals(Availability.UNAVAILABLE_AT_PATH, result.capabilities().getFirst().availability());
        assertEquals(1, result.coverage().entriesExamined());
        assertFalse(result.toString().contains("SAFE"));
        var other = service.discover("two", SELECTOR, "now-1s", "now", null);
        assertEquals(Availability.AVAILABLE, other.capabilities().getFirst().availability());
        assertEquals(1, other.coverage().sampleLimit());
        assertEquals(UNKNOWN, other.coverage().sampleCompleteness());
    }
    @Test void sampleFailureWarningsAndUncalledCapabilitiesAreExplicit() {
        when(client.series(anyString(), anyList(), any(), any())).thenReturn(new SeriesResponse(List.of(Map.of("job", "test")), List.of("SECRET")));
        when(client.queryRange(anyString(), anyString(), any(), any(), anyInt(), any(), any()))
                .thenThrow(Errors.failure(ErrorCode.UPSTREAM_TIMEOUT, "SECRET"));
        var result = discover();
        assertEquals(UNKNOWN, result.coverage().sampleCompleteness());
        assertEquals(0, result.coverage().entriesExamined());
        assertEquals(ErrorCode.UPSTREAM_TIMEOUT, result.capabilities().get(1).errorCode());
        assertTrue(result.capabilities().subList(2, 6).stream().allMatch(c -> c.availability() == Availability.UNKNOWN));
        assertFalse(result.toString().contains("SECRET"));
    }
    @Test void validatesSelectorsWindowsAndConnectionLimitsBeforeNetwork() {
        for (String selector : List.of("{}", "sum(rate({job=\"x\"}[1m]))", "{job=\"x\"} | json", "{job=\"x\"} |= \"foo\"", "{job=\"x\"} garbage"))
            assertThrows(LokiOperationException.class, () -> service.discover("one", selector, "now-1s", "now", null));
        assertThrows(LokiOperationException.class, () -> service.discover(null, SELECTOR, "now-1s", "now", null));
        assertThrows(LokiOperationException.class, () -> service.discover("missing", SELECTOR, "now-1s", "now", null));
        assertThrows(LokiOperationException.class, () -> service.discover("two", SELECTOR, "now-11s", "now", null));
        for (int limit : new int[]{0, -1, 21}) assertThrows(LokiOperationException.class, () -> service.discover("one", SELECTOR, "now-1s", "now", limit));
        assertThrows(LokiOperationException.class, () -> service.discover("two", SELECTOR, "now-1s", "now", 2));
        verifyNoInteractions(client);
    }
    @Test void matcherQuotedBracesCommasAndEscapesAreNotPipelines() {
        series(List.of()); entries(List.of());
        for (String selector : List.of("{job=~\"a|b\", pod!=\"x\"}", "{job=\"a}b,c\\\"d\"}", "{job!~\"foo\"}"))
            assertEquals(selector, service.discover("one", selector, "now-1s", "now", null).selector());
        assertDoesNotThrow(() -> service.discover("one", "{job=\"" + "x".repeat(8000) + "\"}", "now-1s", "now", null));
        assertThrows(LokiOperationException.class, () -> service.discover("one", "{job=\"" + "x".repeat(9000) + "\"}", "now-1s", "now", null));
    }
    @Test void cancellationStopsFurtherCalls() {
        when(client.series(anyString(), anyList(), any(), any())).thenThrow(Errors.failure(ErrorCode.OPERATION_CANCELLED, "safe"));
        assertThrows(LokiOperationException.class, this::discover);
        verify(client).series(eq("one"), anyList(), any(), any()); verifyNoMoreInteractions(client);
    }
}
