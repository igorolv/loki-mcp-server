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
        assertTrue(raw.contains("12:00:00.123 {app=\"x\"}  " + json), raw);
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
        // 12-minute window 11:48:00.123–12:00:00.123: 60s buckets aligned to the clock, evaluated at 11:49:00 .. 12:01:00,
        // the same timestamps Loki itself aligns metric queries to.
        var samples = new java.util.ArrayList<MetricSample>();
        Instant aligned = Instant.parse("2026-09-13T11:48:00Z");
        for (int i = 1; i <= 12; i++) samples.add(new MetricSample(BigDecimal.valueOf(aligned.plusSeconds(60L * i).getEpochSecond()), i == 5 ? "340" : "12"));
        when(client.queryRange(eq("one"), anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new QueryResponse(new Matrix(List.of(new MetricSeries(Map.of(), samples))), null, List.of()));
        var text = service.count("one", "{app=\"x\"}", "now-12m", "now", "time");
        verify(client).queryRange("one", "sum(count_over_time({app=\"x\"} [60s]))", aligned.plusSeconds(60), aligned.plusSeconds(780), 3,
                LokiHttpClient.Direction.FORWARD, new BigDecimal("60.000"));
        assertTrue(text.startsWith("472 lines match {app=\"x\"} in 2026-09-13 11:48:00–12:01:00 (Z) (one)."), text);
        assertTrue(text.contains("By time (60s buckets, bucket start):"), text);
        assertTrue(text.contains("\n  11:48      12\n"), text);
        assertTrue(text.contains("\n  11:52     340  <- spike\n"), text);
        assertTrue(text.endsWith("\n  12:00       0"), text);
        assertEquals(13, text.lines().filter(l -> l.startsWith("  1")).count());
        when(client.queryRange(eq("one"), anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new QueryResponse(new Matrix(List.of()), null, List.of()));
        assertEquals("0 lines match {app=\"x\"} in 2026-09-13 11:48:00–12:01:00 (Z) (one).", service.count("one", "{app=\"x\"}", "now-12m", "now", "time"));
        // A 6-hour window gets 30-minute buckets, a 15-minute window 2-minute ones.
        assertEquals(Duration.ofMinutes(30), QueryService.niceStep(Duration.ofHours(6), QueryService.TIME_BUCKETS));
        assertEquals(Duration.ofMinutes(2), QueryService.niceStep(Duration.ofMinutes(15), QueryService.TIME_BUCKETS));
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
    @Test void contextReadsLinesAroundTheMomentInTwoQueriesAndMarksTheTarget() {
        Instant at = Instant.parse("2026-09-13T11:59:59.123Z");
        String selector = "{app=\"x\"}";
        when(client.queryRange("one", selector, at.plusMillis(1).minusSeconds(7200), at.plusMillis(1), 2, LokiHttpClient.Direction.BACKWARD, null))
                .thenReturn(new QueryResponse(new Streams(List.of(new LogStream(Map.of("app", "x"), List.of(
                        entry(QueryTime.nanos(at.plusNanos(456789)), "target"), entry(QueryTime.nanos(at.minusMillis(1123)), "earlier"))))), null, List.of()));
        when(client.queryRange("one", selector, at.plusMillis(1), at.plusMillis(1).plusSeconds(7200), 1, LokiHttpClient.Direction.FORWARD, null))
                .thenReturn(new QueryResponse(new Streams(List.of(new LogStream(Map.of("app", "x"), List.of(
                        entry(QueryTime.nanos(at.plusMillis(877)), "later"), entry(QueryTime.nanos(at.plusSeconds(5)), "too far"))))), null, List.of()));
        assertEquals("""
                Context in {app="x"} around 2026-09-13 11:59:59.123 (Z) — one, lines: 1 before, 1 at that time, 1 after:
                11:59:58.000 -     x  earlier
                >>> 11:59:59.123 -     x  target
                12:00:00.000 -     x  later
                Earlier: repeat with time="2026-09-13T11:59:58.000+00:00", after=0. Later: repeat with time="2026-09-13T12:00:00.000+00:00", before=0. Full original line: queryLogs with raw=true and a narrow filter.""",
                service.context("one", selector, "11:59:59.123", 1, 1));
        // The same moment as RFC3339 with milliseconds; after=0 skips the forward query.
        assertTrue(service.context("one", selector, "2026-09-13T11:59:59.123Z", 1, 0).contains("\n>>> 11:59:59.123 -     x  target\nEarlier:"));
        verify(client, times(2)).queryRange(eq("one"), eq(selector), any(), any(), eq(2), eq(LokiHttpClient.Direction.BACKWARD), isNull());
        verify(client, times(1)).queryRange(eq("one"), eq(selector), any(), any(), eq(1), eq(LokiHttpClient.Direction.FORWARD), isNull());
    }
    @Test void contextWithoutAnExactLineMarksThePlaceAndExplainsMissingNeighbours() {
        Instant at = Instant.parse("2026-09-13T11:59:59Z");
        when(client.queryRange(eq("one"), anyString(), any(), any(), anyInt(), eq(LokiHttpClient.Direction.BACKWARD), isNull()))
                .thenReturn(new QueryResponse(new Streams(List.of(new LogStream(Map.of("app", "x"), List.of(entry(QueryTime.nanos(at.minusSeconds(90000)), "old"))))), null, List.of()));
        when(client.queryRange(eq("one"), anyString(), any(), any(), anyInt(), eq(LokiHttpClient.Direction.FORWARD), isNull()))
                .thenReturn(new QueryResponse(new Streams(List.of()), null, List.of()));
        assertEquals("""
                Context in {app="x"} around 2026-09-13 11:59:59 (Z) — one, lines: 1 before, 0 at that time, 0 after:
                10:59:59.000 -     x  old
                >>> (no line at exactly this time in {app="x"}; lines before and after it follow)
                No earlier lines within 2h before this time. No later lines within 2h after this time. Full original line: queryLogs with raw=true and a narrow filter.""",
                service.context("one", "{app=\"x\"}", "11:59:59", 2, 2));
        verify(client).queryRange("one", "{app=\"x\"}", at.plusSeconds(1).minusSeconds(7200), at.plusSeconds(1), 3, LokiHttpClient.Direction.BACKWARD, null);
        verify(client).queryRange("one", "{app=\"x\"}", at.plusSeconds(1), at.plusSeconds(7201), 2, LokiHttpClient.Direction.FORWARD, null);
        // Without a line at the moment the spare slot is not shown as an extra "before" line.
        when(client.queryRange(eq("one"), anyString(), any(), any(), anyInt(), eq(LokiHttpClient.Direction.BACKWARD), isNull()))
                .thenReturn(new QueryResponse(new Streams(List.of(new LogStream(Map.of("app", "x"), List.of(
                        entry(QueryTime.nanos(at.minusSeconds(3)), "oldest"), entry(QueryTime.nanos(at.minusSeconds(2)), "b"), entry(QueryTime.nanos(at.minusSeconds(1)), "c"))))), null, List.of()));
        var spare = service.context("one", "{app=\"x\"}", "11:59:59", 2, 0);
        assertTrue(spare.contains("lines: 2 before, 0 at that time, 0 after:\n11:59:57.000") && !spare.contains("oldest"), spare);
        assertTrue(spare.contains("Earlier: repeat with time=\"2026-09-13T11:59:57.000+00:00\", after=0."), spare);
        // Every fetched line shares the (second-precision) moment: the model is told to pass milliseconds.
        when(client.queryRange(eq("one"), anyString(), any(), any(), anyInt(), eq(LokiHttpClient.Direction.BACKWARD), isNull()))
                .thenReturn(new QueryResponse(new Streams(List.of(new LogStream(Map.of("app", "x"), List.of(
                        entry(QueryTime.nanos(at.plusMillis(1)), "a"), entry(QueryTime.nanos(at.plusMillis(2)), "b"), entry(QueryTime.nanos(at.plusMillis(3)), "c"))))), null, List.of()));
        var same = service.context("one", "{app=\"x\"}", "11:59:59", 2, 0);
        assertTrue(same.contains("lines: 0 before, 3 at that time, 0 after:\n>>> 11:59:59.001"), same);
        assertTrue(same.contains("All 3 fetched lines are at this time; pass the time with milliseconds as printed by queryLogs to see what came before."), same);
        // With millisecond precision the lines really share the moment (a plain-text stack trace): ask for more lines, not a narrower selector.
        var burst = service.context("one", "{app=\"x\"}", "11:59:59.001", 2, 0);
        assertTrue(burst.contains("lines: 0 before, 3 at that time, 0 after:"), burst);
        assertTrue(burst.contains("All 3 fetched lines are at this time; repeat with a larger before (e.g. before=15) to see what came before."), burst);
    }
    @Test void contextValidatesBeforeNetworkAndKeepsTheTargetWhenTrimmingForTheBudget() {
        assertTrue(assertThrows(LokiOperationException.class, () -> service.context("one", "{app=\"x\"} |= \"ERROR\"", "11:59:59", null, null)).getMessage().contains("stream selector only"));
        assertTrue(assertThrows(LokiOperationException.class, () -> service.context("one", "{app=\"x\"} | json", "11:59:59", null, null)).getMessage().contains("stream selector only"));
        assertTrue(assertThrows(LokiOperationException.class, () -> service.context("one", "", "11:59:59", null, null)).getMessage().contains("selector is required"));
        assertTrue(assertThrows(LokiOperationException.class, () -> service.context("one", "{app=\"x\"}", " ", null, null)).getMessage().contains("time is required"));
        assertTrue(assertThrows(LokiOperationException.class, () -> service.context("one", "{app=\"x\"}", "noon", null, null)).getMessage().contains("Cannot parse time"));
        assertTrue(assertThrows(LokiOperationException.class, () -> service.context("one", "{app=\"x\"}", "11:59:59", 3, 0)).getMessage().contains("between 0 and 2"));
        assertTrue(assertThrows(LokiOperationException.class, () -> service.context("one", "{app=\"x\"}", "11:59:59", 0, -1)).getMessage().contains("between 0 and 2"));
        assertThrows(LokiOperationException.class, () -> service.context("missing", "{app=\"x\"}", "11:59:59", null, null));
        verifyNoInteractions(client);
        // Connection two: 1024-byte budget, Moscow time. Defaults of 20/20 are trimmed evenly around the marked line.
        Instant at = Instant.parse("2026-09-13T08:59:59.123Z"); // 11:59:59.123 in Moscow
        var before = new java.util.ArrayList<LogEntry>(); var after = new java.util.ArrayList<LogEntry>();
        for (int i = 1; i <= 20; i++) before.add(entry(QueryTime.nanos(at.minusSeconds(i)), "before " + i + " " + "b".repeat(10)));
        before.add(entry(QueryTime.nanos(at), "target line"));
        for (int i = 1; i <= 20; i++) after.add(entry(QueryTime.nanos(at.plusSeconds(i)), "after " + i + " " + "a".repeat(10)));
        when(client.queryRange(eq("two"), anyString(), any(), any(), eq(21), eq(LokiHttpClient.Direction.BACKWARD), isNull()))
                .thenReturn(new QueryResponse(new Streams(List.of(new LogStream(Map.of("app", "x"), before))), null, List.of()));
        when(client.queryRange(eq("two"), anyString(), any(), any(), eq(20), eq(LokiHttpClient.Direction.FORWARD), isNull()))
                .thenReturn(new QueryResponse(new Streams(List.of(new LogStream(Map.of("app", "x"), after))), null, List.of()));
        var text = service.context("two", "{app=\"x\"}", "11:59:59.123", null, null);
        assertTrue(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024 - LogText.ENVELOPE_BYTES, text);
        assertTrue(text.startsWith("Context in {app=\"x\"} around 2026-09-13 11:59:59.123 (+03:00) — two, lines: 20 before, 1 at that time, 20 after:\n"), text);
        assertTrue(text.contains("\n>>> 11:59:59.123 -     x  target line\n"), text);
        assertTrue(text.contains("before 1 ") && text.contains("after 1 ") && !text.contains("before 20 ") && !text.contains("after 20 "), text);
        assertTrue(text.contains("Output limit reached: showing "), text);
        assertTrue(text.contains("Earlier: repeat with time=\"2026-09-13T11:59:5") && text.contains("Later: repeat with time=\"2026-09-13T12:00:0"), text);
    }
}
