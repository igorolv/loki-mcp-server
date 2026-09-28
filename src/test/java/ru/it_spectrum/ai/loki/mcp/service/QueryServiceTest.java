package ru.it_spectrum.ai.loki.mcp.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionAuth;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionDefinition;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionLimits;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionsLoader;
import ru.it_spectrum.ai.loki.mcp.error.LokiOperationException;
import ru.it_spectrum.ai.loki.mcp.model.LogEvent;

import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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

    @Test
    void compactPageUsesTheSelectedJsonProfile() {
        var definition = new ConnectionDefinition("json", null, null, URI.create("http://localhost:6"),
                ConnectionAuth.NONE, null, ZoneId.of("UTC"), ConnectionLimits.DEFAULTS, List.of("app"),
                List.of(), ConnectionsLoader.loadJsonFormats(Path.of("examples/log-formats.json")), null);
        var query = new QueryService(new ConnectionRegistry(List.of(definition)), client,
                Clock.fixed(now, ZoneOffset.UTC));
        String gelf = "{\"version\":\"1.1\",\"short_message\":\"gelf line\",\"level\":6,\"_level_name\":\"INFO\"}";
        range(new Streams(List.of(new LogStream(Map.of("app", "backend"),
                List.of(entry(QueryTime.nanos(now.minusSeconds(1)), gelf))))));
        String page = query.logs("json", "{app=\"backend\"}", "now-1m", "now", 10, null);
        assertTrue(page.contains("INFO"), page);
        assertTrue(page.contains("gelf line"), page);
        assertFalse(page.contains("short_message"), page);
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

    @ParameterizedTest
    @ValueSource(strings = {"newest", "oldest"})
    void lookaheadDistinguishesShortExactAndLongPagesAcrossStreams(String order) {
        String first = QueryTime.nanos(now.minusSeconds(3));
        String second = QueryTime.nanos(now.minusSeconds(2));
        var one = new LogStream(Map.of("app", "a"), List.of(entry(first, "first")));
        var two = new LogStream(Map.of("app", "b"), List.of(entry(second, "second")));
        var three = new LogStream(Map.of("app", "c"), List.of(entry(QueryTime.nanos(now.minusSeconds(1)), "third")));
        var direction = order.equals("oldest") ? LokiHttpClient.Direction.FORWARD : LokiHttpClient.Direction.BACKWARD;

        range(new Streams(List.of(one)));
        String shortPage = service.logs("one", "{app=~\".+\"}", "now-1h", "now", 2, false, order);
        assertTrue(shortPage.contains(", all 1 lines:"), shortPage);
        assertTrue(shortPage.endsWith("Shown all 1 matching lines."), shortPage);
        verify(client).queryRange("one", "{app=~\".+\"}", now.minusSeconds(3600), now, 3, direction, null);

        clearInvocations(client);
        range(new Streams(List.of(one, two)));
        String exactPage = service.logs("one", "{app=~\".+\"}", "now-1h", "now", 2, false, order);
        assertTrue(exactPage.contains(", all 2 lines:"), exactPage);
        assertTrue(exactPage.endsWith("Shown all 2 matching lines."), exactPage);
        verify(client).queryRange("one", "{app=~\".+\"}", now.minusSeconds(3600), now, 3, direction, null);

        clearInvocations(client);
        range(new Streams(List.of(one, two, three)));
        String longPage = service.logs("one", "{app=~\".+\"}", "now-1h", "now", 2, false, order);
        assertTrue(longPage.contains(order + " 2 lines (more exist):"), longPage);
        assertEquals(2, longPage.lines().filter(line -> line.contains("  first") || line.contains("  second")
                || line.contains("  third")).count(), longPage);
        assertFalse(longPage.contains(order.equals("oldest") ? "  third" : "  first"), longPage);
        assertTrue(longPage.contains(order.equals("oldest") ? "Newer: repeat with start=\""
                : "Older: repeat with end=\""), longPage);
        verify(client).queryRange("one", "{app=~\".+\"}", now.minusSeconds(3600), now, 3, direction, null);

        clearInvocations(client);
        String cappedPage = service.logs("one", "{app=~\".+\"}", "now-1h", "now", 3, false, order);
        assertTrue(cappedPage.contains(order + " 3 lines (more may exist):"), cappedPage);
        assertEquals(3, cappedPage.lines().filter(line -> line.contains("  first") || line.contains("  second")
                || line.contains("  third")).count(), cappedPage);
        verify(client).queryRange("one", "{app=~\".+\"}", now.minusSeconds(3600), now, 3, direction, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"newest", "oldest"})
    void lookaheadDoesNotClaimToPageThroughOneCrowdedTimestamp(String order) {
        String timestamp = QueryTime.nanos(now.minusSeconds(1));
        range(new Streams(List.of(
                new LogStream(Map.of("app", "a"), List.of(entry(timestamp, "duplicate"), entry(timestamp, "duplicate"))),
                new LogStream(Map.of("app", "b"), List.of(entry(timestamp, "other"))))));

        String text = service.logs("one", "{app=~\".+\"}", "now-1h", "now", 2, false, order);

        assertTrue(text.contains(order + " 2 lines (more exist):"), text);
        assertEquals(2, text.lines().filter(line -> line.startsWith("11:59:59.123")).count(), text);
        assertTrue(text.contains("boundary lines may repeat. If the same timestamp fills every page, narrow the query."), text);
        verify(client).queryRange("one", "{app=~\".+\"}", now.minusSeconds(3600), now, 3,
                order.equals("oldest") ? LokiHttpClient.Direction.FORWARD : LokiHttpClient.Direction.BACKWARD, null);
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
        assertTrue(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024 - ResponseText.ENVELOPE_BYTES);
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
    void oldestOrderUsesForwardQueryAndStartContinuation() {
        String a = QueryTime.nanos(now.minusSeconds(2));
        String b = QueryTime.nanos(now.minusSeconds(1));
        range(new Streams(List.of(new LogStream(Map.of("app", "x"),
                List.of(entry(a, "first"), entry(b, "second"))))));

        String text = service.logs("one", "{app=\"x\"}", "now-1h", "now", 2, false, "oldest");

        assertTrue(text.contains("all 2 lines:"), text);
        assertTrue(text.endsWith("Shown all 2 matching lines."), text);
        verify(client).queryRange("one", "{app=\"x\"}", now.minusSeconds(3600), now, 3,
                LokiHttpClient.Direction.FORWARD, null);
        assertThrows(LokiOperationException.class,
                () -> service.logs("one", "{app=\"x\"}", null, null, null, null, "ascending"));
    }

    @Test
    void oldestOrderKeepsEarliestLinesWhenResponseBudgetCutsPage() {
        var entries = new java.util.ArrayList<LogEntry>();
        for (int i = 0; i < 41; i++) {
            entries.add(entry(QueryTime.nanos(now.minusSeconds(41 - i)),
                    "line " + i + " " + "x".repeat(80)));
        }
        range(new Streams(List.of(new LogStream(Map.of("app", "x"), entries))));

        String text = service.logs("tight", "{app=\"x\"}", "now-1h", "now", 40, false, "oldest");

        assertTrue(ResponseText.bytes(text) <= 2048 - ResponseText.ENVELOPE_BYTES, text);
        assertTrue(text.contains("line 0 "), text);
        assertFalse(text.contains("line 39 "), text);
        assertTrue(text.contains("Output limit reached: showing "), text);
        assertTrue(text.contains(" oldest of 40 fetched lines. Newest shown "), text);
        assertFalse(text.contains("line 40 "), text);
        assertTrue(text.contains("Newer: repeat with start=\""), text);
    }

    @Test
    void configuredFramePatternFoldsOnlyAdjacentResultLabelsInCompactView() {
        var frames = new ConnectionDefinition("frames", null, null, URI.create("http://localhost:7"),
                ConnectionAuth.NONE, null, ZoneOffset.UTC, ConnectionLimits.DEFAULTS,
                ConnectionDefinition.DEFAULT_SERVICE_LABELS, List.of(), Pattern.compile("^\\s+at\\s+.+$"));
        var reading = new QueryService(new ConnectionRegistry(List.of(frames)), client, Clock.fixed(now, ZoneOffset.UTC));
        range(new Streams(List.of(
                new LogStream(Map.of("app", "a"), List.of(
                        entry(QueryTime.nanos(now.minusSeconds(4)), "Exception"),
                        entry(QueryTime.nanos(now.minusSeconds(3)), "\tat first"),
                        entry(QueryTime.nanos(now.minusSeconds(2)), "\tat second"))),
                new LogStream(Map.of("app", "b"), List.of(
                        entry(QueryTime.nanos(now.minusSeconds(1)), "\tat other"))))));

        String compact = reading.logs("frames", "{app=~\".+\"}", "now-1h", "now", 10, false);
        String raw = reading.logs("frames", "{app=~\".+\"}", "now-1h", "now", 10, true);

        assertTrue(compact.contains("… 2 stack frame lines"), compact);
        assertFalse(compact.contains("at first"), compact);
        assertTrue(compact.contains("at other"), compact);
        assertTrue(compact.endsWith("Shown all 4 matching lines."), compact);
        assertTrue(raw.contains("at first") && raw.contains("at second"), raw);
        assertFalse(raw.contains("stack frame lines"), raw);
    }

}
