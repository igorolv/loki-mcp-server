package ru.it_spectrum.ai.loki.mcp.service;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionAuth;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionDefinition;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionLimits;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.error.LokiOperationException;

import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ru.it_spectrum.ai.loki.mcp.client.LokiResponses.*;

class CountServiceTest {
    private final LokiHttpClient client = mock(LokiHttpClient.class);
    private final Instant now = Instant.parse("2026-09-13T12:00:00.123456789Z");
    private final ConnectionRegistry registry = new ConnectionRegistry(List.of(
            new ConnectionDefinition("one", null, URI.create("http://localhost:1"), ConnectionAuth.NONE, null,
                    ZoneId.of("UTC"), new ConnectionLimits(100, 100, 10000, 4096, 3, 7200)),
            new ConnectionDefinition("three", null, URI.create("http://localhost:3"), ConnectionAuth.NONE, null,
                    ZoneId.of("Europe/Moscow"), new ConnectionLimits(100, 100, 10000, 65536, 1000, 86400))));
    private final CountService counts = new CountService(registry, client, Clock.fixed(now, ZoneOffset.UTC));

    private void range(QueryData data) {
        doReturn(new QueryResponse(data))
                .when(client).queryRange(anyString(), anyString(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void countBuildsTheMetricExpressionAndRendersTotalsAndGroups() {
        when(client.queryInstant(eq("one"), anyString(), any())).thenReturn(new QueryResponse(new Vector(List.of(
                new VectorSample(Map.of(), new MetricSample(BigDecimal.ONE, "1523"))))));
        assertEquals("1523 lines match {app=\"x\"} |= \"ERROR\" in 2026-09-13 11:45:00–12:00:00 (Z) (one).",
                counts.count("one", "{app=\"x\"} |= \"ERROR\"", "now-15m", "now", null));
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
                  (none)  7""", counts.count("one", "{app=\"x\"}", "now-15m", "now", "level"));
        verify(client).queryInstant("one", "sum by (level) (count_over_time({app=\"x\"} [900s]))", now);
        when(client.queryInstant(eq("one"), anyString(), any())).thenReturn(new QueryResponse(new Vector(List.of())));
        assertEquals("0 lines match {app=\"x\"} in 2026-09-13 11:45:00–12:00:00 (Z) (one).", counts.count("one", "{app=\"x\"}", "now-15m", "now", "level"));
        assertThrows(LokiOperationException.class, () -> counts.count("one", "{app=\"x\"}", null, null, "bad-name"));
        assertThrows(LokiOperationException.class, () -> counts.count("one", "count_over_time({app=\"x\"}[1m])", null, null, null));
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
        var text = counts.count("one", "{app=\"x\"}", "now-12m", "now", "time");
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
        assertTrue(counts.count("one", "{app=\"x\"}", "now-12m", "now", "time").startsWith("0 lines match "));
        // A 6-hour window gets 30-minute buckets, a 15-minute window 2-minute ones.
        assertEquals(Duration.ofMinutes(30), CountService.niceStep(Duration.ofHours(6), CountService.TIME_BUCKETS));
        assertEquals(Duration.ofMinutes(2), CountService.niceStep(Duration.ofMinutes(15), CountService.TIME_BUCKETS));
    }

    @Test
    void countByTimeMarksDayChangeWhenClockLabelsRepeat() {
        range(new Matrix(List.of()));

        String text = counts.count("three", "{app=\"x\"}", "now-24h", "now", "time");

        assertTrue(text.contains("\n--- 2026-09-13 ---\n  01:00"), text);
        assertEquals(2, text.lines().filter(line -> line.startsWith("  15:00")).count(), text);
        assertEquals(1, text.lines().filter(line -> line.equals("--- 2026-09-13 ---")).count(), text);
    }

    @Test
    void defaultCountWindowSeparatesTimeOnlyBucketsFromOtherCounts() {
        var defaults = new ConnectionRegistry(List.of(new ConnectionDefinition("default", null,
                URI.create("http://localhost:6"), ConnectionAuth.NONE, null, ZoneOffset.UTC,
                ConnectionLimits.DEFAULTS)));
        var counting = new CountService(defaults, client, Clock.fixed(now, ZoneOffset.UTC));
        when(client.queryInstant(eq("default"), anyString(), any()))
                .thenReturn(new QueryResponse(new Vector(List.of(new VectorSample(Map.of(),
                        new MetricSample(BigDecimal.ONE, "7"))))));

        assertTrue(counting.count("default", "{app=\"x\"}", "now-1d", "now", null).startsWith("7 lines match"));
        verify(client).queryInstant("default", "sum(count_over_time({app=\"x\"} [86400s]))", now);
        assertThrows(LokiOperationException.class,
                () -> counting.count("default", "{app=\"x\"}", "now-3d", "now", null));
        assertThrows(LokiOperationException.class,
                () -> counting.count("default", "{app=\"x\"}", "now-3d", "now", "app"));
        assertThrows(LokiOperationException.class,
                () -> counting.count("default", "{app=\"x\"}", "now-3d", "now", "app,time"));
        when(client.queryRange(eq("default"), anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new QueryResponse(new Matrix(List.of())));
        assertTrue(counting.count("default", "{app=\"x\"}", "now-3d", "now", "time")
                .startsWith("0 lines match"));
        assertThrows(LokiOperationException.class,
                () -> new QueryService(defaults, client, Clock.fixed(now, ZoneOffset.UTC))
                        .logs("default", "{app=\"x\"}", "now-3d", "now", null, null));
    }

    @Test
    void explicitStepGroupsByLabelAndTime() {
        Instant first = Instant.parse("2026-09-13T00:00:00Z");
        Instant last = Instant.parse("2026-09-14T00:00:00Z");
        range(new Matrix(List.of(
                new MetricSeries(Map.of("app", "backend"), List.of(
                        new MetricSample(BigDecimal.valueOf(first.getEpochSecond()), "3"),
                        new MetricSample(BigDecimal.valueOf(last.getEpochSecond()), "5"))),
                new MetricSeries(Map.of("app", "frontend"), List.of(
                        new MetricSample(BigDecimal.valueOf(first.getEpochSecond()), "2"))))));

        String text = counts.count("three", "{app=~\".+\"}", "now-24h", "now", "app,time", "1d");

        assertTrue(text.startsWith("10 lines match"), text);
        assertTrue(text.contains("By app and time (86400s buckets, bucket start):"), text);
        assertTrue(text.contains("  03:00  backend       3"), text);
        assertTrue(text.contains("--- 2026-09-13 ---"), text);
        verify(client).queryRange(eq("three"), eq("sum by (app) (count_over_time({app=~\".+\"} [86400s]))"),
                any(), any(), eq(1000), eq(LokiHttpClient.Direction.FORWARD), eq(new BigDecimal("86400.000")));
        assertThrows(LokiOperationException.class,
                () -> counts.count("three", "{app=\"x\"}", "now-24h", "now", "app", "1h"));
        assertThrows(LokiOperationException.class,
                () -> counts.count("three", "{app=\"x\"}", "now-24h", "now", "time", "1s"));
        assertThrows(LokiOperationException.class,
                () -> counts.count("three", "{app=\"x\"}", "now-24h", "now", "time",
                        "999999999999999999999s"));
    }

    @Test
    void combinedBucketsReportHiddenRowsAndValues() {
        Instant first = Instant.parse("2026-09-12T18:00:00Z");
        var series = new java.util.ArrayList<MetricSeries>();
        for (int value = 0; value < 60; value++) {
            var samples = new java.util.ArrayList<MetricSample>();
            for (int bucket = 0; bucket < 5; bucket++) {
                samples.add(new MetricSample(BigDecimal.valueOf(first.plusSeconds(bucket * 21_600L)
                        .getEpochSecond()), "1"));
            }
            series.add(new MetricSeries(Map.of("app", String.format("app-%02d", value)), samples));
        }
        range(new Matrix(series));

        String text = counts.count("three", "{app=~\".+\"}", "now-24h", "now", "app,time", "6h");

        assertTrue(text.startsWith("300 lines match"), text);
        assertTrue(text.contains("top 50 values"), text);
        assertTrue(text.contains("Output limit reached: showing 200 of 250 rows; +10 more values; "
                + "total includes hidden counts."), text);
    }

    @Test
    void niceStepCoversTheWindowWithTheAskedPoints() {
        assertEquals(Duration.ofSeconds(30), CountService.niceStep(Duration.ofMinutes(10), 20));
        assertEquals(Duration.ofMinutes(5), CountService.niceStep(Duration.ofHours(1), 20));
        assertEquals(Duration.ofHours(1), CountService.niceStep(Duration.ofHours(20), 20));
        assertEquals(Duration.ofDays(1), CountService.niceStep(Duration.ofDays(60), 20));
        assertEquals(Duration.ofSeconds(1), CountService.niceStep(Duration.ofSeconds(5), 20));
    }
}
