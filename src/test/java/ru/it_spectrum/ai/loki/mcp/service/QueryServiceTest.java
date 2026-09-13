package ru.it_spectrum.ai.loki.mcp.service;

import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.loki.mcp.client.*;
import ru.it_spectrum.ai.loki.mcp.connection.*;
import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;
import static ru.it_spectrum.ai.loki.mcp.client.LokiResponses.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class QueryServiceTest {
    private final LokiHttpClient client = mock(LokiHttpClient.class);
    private final Instant now = Instant.parse("2026-09-13T12:00:00.123456789Z");
    private final ConnectionRegistry registry = new ConnectionRegistry(List.of(
            new ConnectionDefinition("one", null, URI.create("http://localhost:1"), ConnectionAuth.NONE, null,
                    ZoneId.of("UTC"), new ConnectionLimits(100, 100, 10000, 4096, 3, 7200, 2, 25)),
            new ConnectionDefinition("two", null, URI.create("http://localhost:2"), ConnectionAuth.NONE, null,
                    ZoneId.of("Europe/Moscow"), new ConnectionLimits(100, 100, 10000, 1024, 100, 86400, 100, 1000))));
    private final QueryService service = new QueryService(registry, client, Clock.fixed(now, ZoneOffset.UTC));
    private void range(QueryData data, List<String> warnings) {
        when(client.queryRange(anyString(), anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new QueryResponse(data, new QueryStats(999L), warnings));
    }
    private static LogEntry entry(String nanos, String line) { return new LogEntry(nanos, line, Map.of()); }

    @Test void pageIsChronologicalWithHeaderAndFooterAndKeepsDuplicates() {
        String a = QueryTime.nanos(now.minusSeconds(1)), b = QueryTime.nanos(now);
        range(new Streams(List.of(new LogStream(Map.of("app", "x", "level", "info"), List.of(entry(b, "{\"message\":\"newest\"}"))),
                new LogStream(Map.of("app", "y"), List.of(entry(a, "dup"), entry(a, "dup"))))), List.of());
        var text = service.logs("one", "{app=~\".+\"}", "now-1s", "now", 3, null);
        assertEquals("""
                {app=~".+"} — one, 2026-09-13 11:59:59–12:00:00 (Z), newest 3 of more:
                11:59:59.123 -     y  dup
                11:59:59.123 -     y  dup
                12:00:00.123 INFO  x  newest
                Shown 3 newest lines; oldest shown 2026-09-13T11:59:59.123+00:00. Older: repeat with end="2026-09-13T11:59:59.124+00:00". Too many lines? Narrow the query (add a filter or level) or use countLogs.""", text);
        verify(client).queryRange("one", "{app=~\".+\"}", now.minusSeconds(1), now, 3, LokiHttpClient.Direction.BACKWARD, null);
        range(new Streams(List.of(new LogStream(Map.of("app", "y"), List.of(entry(a, "dup"), entry(a, "dup"))))), List.of());
        var fewer = service.logs("one", "{app=~\".+\"}", null, null, null, null);
        assertTrue(fewer.startsWith("{app=~\".+\"} — one, 2026-09-13 11:00:00–12:00:00 (Z), all 2 lines:"), fewer);
        assertTrue(fewer.endsWith("Shown all 2 matching lines."), fewer);
        verify(client).queryRange("one", "{app=~\".+\"}", now.minusSeconds(3600), now, 3, LokiHttpClient.Direction.BACKWARD, null);
    }
    @Test void emptyPageExplainsWhatToTryAndWarningsAreNotEchoed() {
        range(new Streams(List.of()), List.of("SECRET_URL_TOKEN"));
        var text = service.logs("one", "{app=\"x\"}", "now-1s", "now", null, null);
        assertTrue(text.contains("no matching lines."));
        assertTrue(text.contains("Try a wider window"));
        assertFalse(text.contains("SECRET"));
    }
    @Test void rawPrintsOriginalLinesAndBudgetDropsOldestLines() {
        String json = "{\"message\":\"m\",\"extra\":\"" + "x".repeat(600) + "\"}";
        range(new Streams(List.of(new LogStream(Map.of("app", "x"), List.of(entry(QueryTime.nanos(now), json))))), List.of());
        var raw = service.logs("one", "{app=\"x\"}", "now-1s", "now", 1, true);
        assertTrue(raw.contains("12:00:00.123 x  " + json), raw);
        var lines = new java.util.ArrayList<LogEntry>();
        for (int i = 0; i < 100; i++) lines.add(entry(QueryTime.nanos(now.minusSeconds(100 - i)), "line " + i + " " + "y".repeat(30)));
        range(new Streams(List.of(new LogStream(Map.of("app", "x"), lines))), List.of());
        var text = service.logs("two", "{app=\"x\"}", "now-1h", "now", 100, false);
        assertTrue(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024 - LogText.ENVELOPE_BYTES);
        assertTrue(text.contains("line 99 "), text);
        assertFalse(text.contains("line 0 "), text);
        assertTrue(text.contains("Output limit reached: showing "), text);
        assertTrue(text.contains("Older: repeat with end=\"2026-09-13T"), text);
    }
    @Test void validatesBeforeNetworkWithActionableMessages() {
        assertThrows(LokiOperationException.class, () -> service.logs(null, "{a=\"b\"}", null, null, null, null));
        assertThrows(LokiOperationException.class, () -> service.logs("missing", "{a=\"b\"}", null, null, null, null));
        assertTrue(assertThrows(LokiOperationException.class, () -> service.logs("one", "{a=\"b\"}", "now-3h", "now", null, null))
                .getMessage().contains("Window is longer"));
        assertTrue(assertThrows(LokiOperationException.class, () -> service.logs("one", "{a=\"b\"}", null, null, 4, null))
                .getMessage().contains("between 1 and 3"));
        assertTrue(assertThrows(LokiOperationException.class, () -> service.logs("one", "sum(rate({a=\"b\"}[1m]))", null, null, null, null))
                .getMessage().contains("queryMetrics"));
        assertThrows(LokiOperationException.class, () -> service.logs("one", " ", null, null, null, null));
        assertTrue(assertThrows(LokiOperationException.class, () -> service.logs("one", "{a=\"b\"}", "yesterday", null, null, null))
                .getMessage().contains("now-15m"));
        verifyNoInteractions(client);
    }
    @Test void countBuildsTheMetricExpressionAndRendersTotalsAndGroups() {
        when(client.queryInstant(eq("one"), anyString(), any())).thenReturn(new QueryResponse(new Vector(List.of(
                new VectorSample(Map.of(), new MetricSample(BigDecimal.ONE, "1523")))), null, List.of()));
        assertEquals("1523 lines match {app=\"x\"} |= \"ERROR\" in 2026-09-13 11:45:00–12:00:00 (Z) (one).",
                service.count("one", "{app=\"x\"} |= \"ERROR\"", "now-15m", "now", null));
        verify(client).queryInstant("one", "sum(count_over_time({app=\"x\"} |= \"ERROR\" [900s]))", now);
        when(client.queryInstant(eq("one"), anyString(), any())).thenReturn(new QueryResponse(new Vector(List.of(
                new VectorSample(Map.of("level", "warn"), new MetricSample(BigDecimal.ONE, "210")),
                new VectorSample(Map.of("level", "error"), new MetricSample(BigDecimal.ONE, "1523")),
                new VectorSample(Map.of(), new MetricSample(BigDecimal.ONE, "7")))), null, List.of()));
        assertEquals("""
                1740 lines match {app="x"} in 2026-09-13 11:45:00–12:00:00 (Z) (one).
                By level:
                  error   1523
                  warn    210
                  (none)  7""", service.count("one", "{app=\"x\"}", "now-15m", "now", "level"));
        verify(client).queryInstant("one", "sum by (level) (count_over_time({app=\"x\"} [900s]))", now);
        when(client.queryInstant(eq("one"), anyString(), any())).thenReturn(new QueryResponse(new Vector(List.of()), null, List.of()));
        assertEquals("0 lines match {app=\"x\"} in 2026-09-13 11:45:00–12:00:00 (Z) (one).", service.count("one", "{app=\"x\"}", "now-15m", "now", "level"));
        assertThrows(LokiOperationException.class, () -> service.count("one", "{app=\"x\"}", null, null, "bad-name"));
        assertThrows(LokiOperationException.class, () -> service.count("one", "count_over_time({app=\"x\"}[1m])", null, null, null));
    }
    @Test void countByTimeUsesBucketsCoveringTheWindowAndMarksSpikes() {
        // 12-minute window, 12 buckets of 60s evaluated at start+60s .. end.
        var samples = new java.util.ArrayList<MetricSample>();
        Instant start = now.minusSeconds(720);
        for (int i = 1; i <= 12; i++) samples.add(new MetricSample(BigDecimal.valueOf(start.plusSeconds(60L * i).getEpochSecond()), i == 5 ? "340" : "12"));
        when(client.queryRange(eq("one"), anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new QueryResponse(new Matrix(List.of(new MetricSeries(Map.of(), samples))), null, List.of()));
        var text = service.count("one", "{app=\"x\"}", "now-12m", "now", "time");
        verify(client).queryRange("one", "sum(count_over_time({app=\"x\"} [60s]))", start.plusSeconds(60), now, 3,
                LokiHttpClient.Direction.FORWARD, new BigDecimal("60.000"));
        assertTrue(text.startsWith("472 lines match"), text);
        assertTrue(text.contains("By time (60s buckets, bucket start):"), text);
        assertTrue(text.contains("\n  11:48      12\n"), text);
        assertTrue(text.contains("\n  11:52     340  <- spike\n"), text);
        assertEquals(12, text.lines().filter(l -> l.startsWith("  1")).count());
        when(client.queryRange(eq("one"), anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new QueryResponse(new Matrix(List.of()), null, List.of()));
        assertEquals("0 lines match {app=\"x\"} in 2026-09-13 11:48:00–12:00:00 (Z) (one).", service.count("one", "{app=\"x\"}", "now-12m", "now", "time"));
    }
    @Test void metricsRenderSeriesTablesWithNiceStepsAndConnectionCaps() {
        long t0 = now.minusSeconds(600).getEpochSecond();
        var samples = List.of(new MetricSample(new BigDecimal(t0 + ".5"), "0.5"), new MetricSample(BigDecimal.valueOf(t0 + 60), "NaN"),
                new MetricSample(BigDecimal.valueOf(t0 + 120), "+Inf"));
        range(new Matrix(List.of(new MetricSeries(Map.of("level", "error"), samples), new MetricSeries(Map.of("level", "warn"), samples),
                new MetricSeries(Map.of("level", "info"), samples))), List.of());
        var text = service.metrics("one", "sum by (level) (rate({app=\"x\"}[1m]))", "now-10m", "now", null);
        verify(client).queryRange("one", "sum by (level) (rate({app=\"x\"}[1m]))", now.minusSeconds(600), now, 3,
                LokiHttpClient.Direction.FORWARD, new BigDecimal("30.000"));
        assertTrue(text.startsWith("sum by (level) (rate({app=\"x\"}[1m])) — one, 2026-09-13 11:50:00–12:00:00 (Z), step 30s, 3 series:"), text);
        assertTrue(text.contains("""

                {level="error"}
                  11:50:00  0.5
                  11:51:00  NaN
                  11:52:00  +Inf
                {level="warn"}
                  11:50:00  0.5
                  11:51:00  NaN
                  11:52:00  +Inf
                Output trimmed"""), text);
        assertTrue(text.contains("Output trimmed to 2 series / 6 points"), text);
        service.metrics("one", "rate({a=\"b\"}[1m])", "now-10m", "now", "5m");
        verify(client).queryRange("one", "rate({a=\"b\"}[1m])", now.minusSeconds(600), now, 3, LokiHttpClient.Direction.FORWARD, new BigDecimal("300.000"));
        range(new Matrix(List.of()), List.of());
        assertTrue(service.metrics("one", "rate({a=\"b\"}[1m])", "now-10m", "now", "5m").endsWith("no series. The expression matched no data in this window."));
        assertTrue(assertThrows(LokiOperationException.class, () -> service.metrics("one", "{a=\"b\"}", null, null, null)).getMessage().contains("log query"));
        assertTrue(assertThrows(LokiOperationException.class, () -> service.metrics("one", "rate({a=\"b\"}[1m])", "now-10m", "now", "1s")).getMessage().contains("points per series"));
        assertThrows(LokiOperationException.class, () -> service.metrics("one", "rate({a=\"b\"}[1m])", "now-10m", "now", "5x"));
        range(new Streams(List.of()), List.of());
        assertThrows(LokiOperationException.class, () -> service.metrics("one", "rate({a=\"b\"}[1m])", "now-10m", "now", "5m"));
    }
    @Test void niceStepCoversTheWindowWithAboutTwentyPoints() {
        assertEquals(Duration.ofSeconds(30), QueryService.niceStep(Duration.ofMinutes(10)));
        assertEquals(Duration.ofMinutes(5), QueryService.niceStep(Duration.ofHours(1)));
        assertEquals(Duration.ofHours(1), QueryService.niceStep(Duration.ofHours(20)));
        assertEquals(Duration.ofDays(1), QueryService.niceStep(Duration.ofDays(60)));
        assertEquals(Duration.ofSeconds(1), QueryService.niceStep(Duration.ofSeconds(5)));
    }
}
