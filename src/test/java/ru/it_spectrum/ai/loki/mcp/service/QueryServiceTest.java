package ru.it_spectrum.ai.loki.mcp.service;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionAuth;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionDefinition;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionLimits;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.model.LogEvent;

import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ru.it_spectrum.ai.loki.mcp.client.LokiResponses.*;

class QueryServiceTest {
    private final LokiHttpClient client = mock(LokiHttpClient.class);
    private final Instant now = Instant.parse("2026-09-13T12:00:00.123456789Z");
    private final ConnectionRegistry registry = new ConnectionRegistry(List.of(
            new ConnectionDefinition("one", null, URI.create("http://localhost:1"), ConnectionAuth.NONE, null,
                    ZoneId.of("UTC"), new ConnectionLimits(100, 100, 10000, 4096, 3, 7200)),
            new ConnectionDefinition("two", null, URI.create("http://localhost:2"), ConnectionAuth.NONE, null,
                    ZoneId.of("Europe/Moscow"), new ConnectionLimits(100, 100, 10000, 1024, 100, 86400)),
            new ConnectionDefinition("three", null, URI.create("http://localhost:3"), ConnectionAuth.NONE, null,
                    ZoneId.of("Europe/Moscow"), new ConnectionLimits(100, 100, 10000, 65536, 1000, 86400)),
            new ConnectionDefinition("tight", null, URI.create("http://localhost:4"), ConnectionAuth.NONE, null,
                    ZoneId.of("UTC"), new ConnectionLimits(100, 100, 10000, 2048, 1000, 86400)),
            new ConnectionDefinition("paged", null, URI.create("http://localhost:5"), ConnectionAuth.NONE, null,
                    ZoneId.of("UTC"), new ConnectionLimits(100, 100, 200000, 65536, 1000, 86400))));
    private final QueryService service = new QueryService(registry, client, Clock.fixed(now, ZoneOffset.UTC));

    private static LogEntry entry(String nanos, String line) {
        return new LogEntry(nanos, line, Map.of());
    }

    private void range(QueryData data) {
        doReturn(new QueryResponse(data))
                .when(client).queryRange(anyString(), anyString(), any(), any(), anyInt(), any(), any());
    }

    /**
     * A mock Loki that honours end (exclusive) and limit with backward direction, as the paged sample relies on.
     */
    private void pagedRange(Map<String, String> labels, List<LogEntry> entries) {
        doAnswer(invocation -> {
            Instant end = invocation.getArgument(3);
            int limit = invocation.getArgument(4);
            var page = new java.util.ArrayList<LogEntry>();
            for (var e : entries) if (QueryTime.fromNanos(e.timestampNanos()).isBefore(end)) page.add(e);
            page.sort(java.util.Comparator.comparing((LogEntry e) -> Long.parseLong(e.timestampNanos())).reversed());
            if (page.size() > limit) page = new java.util.ArrayList<>(page.subList(0, limit));
            return new QueryResponse(new Streams(List.of(new LogStream(labels, page))));
        }).when(client).queryRange(anyString(), anyString(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void pageIsChronologicalWithHeaderAndFooterAndKeepsDuplicates() {
        String a = QueryTime.nanos(now.minusSeconds(1)), b = QueryTime.nanos(now);
        range(new Streams(List.of(new LogStream(Map.of("app", "x", "level", "info"), List.of(entry(b, "{\"message\":\"newest\"}"))),
                new LogStream(Map.of("app", "y"), List.of(entry(a, "dup"), entry(a, "dup"))))));
        var text = service.logs("one", "{app=~\".+\"}", "now-1s", "now", 3, null);
        assertEquals("""
                {app=~".+"} — one, 2026-09-13 11:59:59–12:00:00 (Z), newest 3 lines (more may exist):
                11:59:59.123 -     y  dup
                11:59:59.123 -     y  dup
                12:00:00.123 INFO  x  newest
                Oldest shown 2026-09-13T11:59:59.123+00:00. Older: repeat with end="2026-09-13T11:59:59.124+00:00"; boundary lines may repeat. If the same timestamp fills every page, narrow the query. For many lines, use countLogs or exportLogs.""", text);
        verify(client).queryRange("one", "{app=~\".+\"}", now.minusSeconds(1), now, 3, LokiHttpClient.Direction.BACKWARD, null);
        range(new Streams(List.of(new LogStream(Map.of("app", "y"), List.of(entry(a, "dup"), entry(a, "dup"))))));
        var fewer = service.logs("one", "{app=~\".+\"}", null, null, null, null);
        assertTrue(fewer.startsWith("{app=~\".+\"} — one, 2026-09-13 11:00:00–12:00:00 (Z), all 2 lines:"), fewer);
        assertTrue(fewer.endsWith("Shown all 2 matching lines."), fewer);
        verify(client).queryRange("one", "{app=~\".+\"}", now.minusSeconds(3600), now, 3, LokiHttpClient.Direction.BACKWARD, null);
    }

    @Test
    void emptyPageExplainsWhatToTry() {
        range(new Streams(List.of()));
        var text = service.logs("one", "{app=\"x\"}", "now-1s", "now", null, null);
        assertTrue(text.contains("no matching lines."));
        assertTrue(text.contains("Try a wider window"));
    }

    @Test
    void rawPrintsOriginalLinesAndBudgetDropsOldestLines() {
        String json = "{\"message\":\"m\",\"extra\":\"" + "x".repeat(600) + "\"}";
        range(new Streams(List.of(new LogStream(Map.of("app", "x"), List.of(entry(QueryTime.nanos(now), json))))));
        var raw = service.logs("one", "{app=\"x\"}", "now-1s", "now", 1, true);
        assertTrue(raw.contains("12:00:00.123 {app=\"x\"}  " + json), raw);
        var lines = new java.util.ArrayList<LogEntry>();
        for (int i = 0; i < 100; i++)
            lines.add(entry(QueryTime.nanos(now.minusSeconds(100 - i)), "line " + i + " " + "y".repeat(30)));
        range(new Streams(List.of(new LogStream(Map.of("app", "x"), lines))));
        var text = service.logs("two", "{app=\"x\"}", "now-1h", "now", 100, false);
        assertTrue(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024 - LogText.ENVELOPE_BYTES);
        assertTrue(text.contains("line 99 "), text);
        assertFalse(text.contains("line 0 "), text);
        assertTrue(text.contains("Output limit reached: showing "), text);
        assertTrue(text.contains("Older: repeat with end=\"2026-09-13T"), text);
    }

    @Test
    void validatesBeforeNetworkWithActionableMessages() {
        assertThrows(LokiOperationException.class, () -> service.logs(null, "{a=\"b\"}", null, null, null, null));
        assertThrows(LokiOperationException.class, () -> service.logs("missing", "{a=\"b\"}", null, null, null, null));
        assertTrue(assertThrows(LokiOperationException.class, () -> service.logs("one", "{a=\"b\"}", "now-3h", "now", null, null))
                .getMessage().contains("Window is longer"));
        assertTrue(assertThrows(LokiOperationException.class, () -> service.logs("one", "{a=\"b\"}", null, null, 4, null))
                .getMessage().contains("between 1 and 3"));
        assertTrue(assertThrows(LokiOperationException.class, () -> service.logs("one", "sum(rate({a=\"b\"}[1m]))", null, null, null, null))
                .getMessage().contains("countLogs"));
        assertThrows(LokiOperationException.class, () -> service.logs("one", " ", null, null, null, null));
        assertTrue(assertThrows(LokiOperationException.class, () -> service.logs("one", "{a=\"b\"}", "yesterday", null, null, null))
                .getMessage().contains("now-15m"));
        verifyNoInteractions(client);
    }

    @Test
    void countBuildsTheMetricExpressionAndRendersTotalsAndGroups() {
        when(client.queryInstant(eq("one"), anyString(), any())).thenReturn(new QueryResponse(new Vector(List.of(
                new VectorSample(Map.of(), new MetricSample(BigDecimal.ONE, "1523"))))));
        assertEquals("1523 lines match {app=\"x\"} |= \"ERROR\" in 2026-09-13 11:45:00–12:00:00 (Z) (one).",
                service.count("one", "{app=\"x\"} |= \"ERROR\"", "now-15m", "now", null));
        verify(client).queryInstant("one", "sum(count_over_time({app=\"x\"} |= \"ERROR\" [900s]))", now);
        when(client.queryInstant(eq("one"), anyString(), any())).thenReturn(new QueryResponse(new Vector(List.of(
                new VectorSample(Map.of("level", "warn"), new MetricSample(BigDecimal.ONE, "210")),
                new VectorSample(Map.of("level", "error"), new MetricSample(BigDecimal.ONE, "1523")),
                new VectorSample(Map.of(), new MetricSample(BigDecimal.ONE, "7"))))));
        assertEquals("""
                1740 lines match {app="x"} in 2026-09-13 11:45:00–12:00:00 (Z) (one).
                By level:
                  error   1523
                  warn    210
                  (none)  7""", service.count("one", "{app=\"x\"}", "now-15m", "now", "level"));
        verify(client).queryInstant("one", "sum by (level) (count_over_time({app=\"x\"} [900s]))", now);
        when(client.queryInstant(eq("one"), anyString(), any())).thenReturn(new QueryResponse(new Vector(List.of())));
        assertEquals("0 lines match {app=\"x\"} in 2026-09-13 11:45:00–12:00:00 (Z) (one).", service.count("one", "{app=\"x\"}", "now-15m", "now", "level"));
        assertThrows(LokiOperationException.class, () -> service.count("one", "{app=\"x\"}", null, null, "bad-name"));
        assertThrows(LokiOperationException.class, () -> service.count("one", "count_over_time({app=\"x\"}[1m])", null, null, null));
    }

    @Test
    void countByTimeUsesClockAlignedBuckets() {
        // 12-minute window 11:48:00.123–12:00:00.123: 60s buckets aligned to the clock, evaluated at 11:49:00 .. 12:01:00,
        // the same timestamps Loki itself aligns metric queries to.
        var samples = new java.util.ArrayList<MetricSample>();
        Instant aligned = Instant.parse("2026-09-13T11:48:00Z");
        for (int i = 1; i <= 12; i++)
            samples.add(new MetricSample(BigDecimal.valueOf(aligned.plusSeconds(60L * i).getEpochSecond()), i == 5 ? "340" : "12"));
        when(client.queryRange(eq("one"), anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new QueryResponse(new Matrix(List.of(new MetricSeries(Map.of(), samples)))));
        var text = service.count("one", "{app=\"x\"}", "now-12m", "now", "time");
        verify(client).queryRange("one", "sum(count_over_time({app=\"x\"} [60s]))", aligned.plusSeconds(60), aligned.plusSeconds(780), 3,
                LokiHttpClient.Direction.FORWARD, new BigDecimal("60.000"));
        assertTrue(text.startsWith("472 lines match {app=\"x\"} in 2026-09-13 11:48:00–12:01:00 (Z) (one)."), text);
        assertTrue(text.contains("By time (60s buckets, bucket start):"), text);
        assertTrue(text.contains("\n  11:48      12\n"), text);
        assertTrue(text.contains("\n  11:52     340\n"), text);
        assertTrue(text.endsWith("\n  12:00       0"), text);
        assertEquals(13, text.lines().filter(l -> l.startsWith("  1")).count());
        when(client.queryRange(eq("one"), anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new QueryResponse(new Matrix(List.of())));
        assertTrue(service.count("one", "{app=\"x\"}", "now-12m", "now", "time").startsWith("0 lines match "));
        // A 6-hour window gets 30-minute buckets, a 15-minute window 2-minute ones.
        assertEquals(Duration.ofMinutes(30), QueryService.niceStep(Duration.ofHours(6), QueryService.TIME_BUCKETS));
        assertEquals(Duration.ofMinutes(2), QueryService.niceStep(Duration.ofMinutes(15), QueryService.TIME_BUCKETS));
    }

    @Test
    void niceStepCoversTheWindowWithTheAskedPoints() {
        assertEquals(Duration.ofSeconds(30), QueryService.niceStep(Duration.ofMinutes(10), 20));
        assertEquals(Duration.ofMinutes(5), QueryService.niceStep(Duration.ofHours(1), 20));
        assertEquals(Duration.ofHours(1), QueryService.niceStep(Duration.ofHours(20), 20));
        assertEquals(Duration.ofDays(1), QueryService.niceStep(Duration.ofDays(60), 20));
        assertEquals(Duration.ofSeconds(1), QueryService.niceStep(Duration.ofSeconds(5), 20));
    }
}
