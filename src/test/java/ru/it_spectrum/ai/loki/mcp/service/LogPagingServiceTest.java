package ru.it_spectrum.ai.loki.mcp.service;

import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.it_spectrum.ai.loki.mcp.client.*;
import ru.it_spectrum.ai.loki.mcp.connection.*;
import ru.it_spectrum.ai.loki.mcp.model.*;
import ru.it_spectrum.ai.loki.mcp.tools.QueryTools;
import io.modelcontextprotocol.spec.McpSchema.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LogPagingServiceTest {
    private final JsonMapper mapper = new JsonMapper();
    private final LokiHttpClient client = mock(LokiHttpClient.class);
    private final AtomicReference<List<QueryResults.Event>> source = new AtomicReference<>(List.of());
    private final Clock clock = Clock.fixed(Instant.ofEpochSecond(100), ZoneOffset.UTC);
    private ConnectionRegistry registry(int cap, int bytes) {
        return new ConnectionRegistry(List.of(new ConnectionDefinition("test", null, URI.create("http://localhost"),
                ConnectionAuth.NONE, null, ZoneOffset.UTC, new ConnectionLimits(100, 100, 1000000, bytes, cap, 3600))));
    }
    private QueryTools tools(int cap, int bytes) {
        when(client.queryRange(eq("test"), anyString(), any(), any(), anyInt(), any(), isNull())).thenAnswer(invocation -> {
            long start = Long.parseLong(QueryTime.nanos(invocation.getArgument(2))), end = Long.parseLong(QueryTime.nanos(invocation.getArgument(3)));
            boolean forward = invocation.getArgument(5) == LokiHttpClient.Direction.FORWARD;
            Comparator<QueryResults.Event> comparator = Comparator.comparingLong(e -> Long.parseLong(e.timestampNanos()));
            if (!forward) comparator = comparator.reversed();
            var matching = source.get().stream().filter(e -> Long.parseLong(e.timestampNanos()) >= start
                    && Long.parseLong(e.timestampNanos()) < end).sorted(comparator).limit((int) invocation.getArgument(4)).toList();
            return new LokiResponses.QueryResponse(new LokiResponses.Streams(matching.stream().map(e ->
                    new LokiResponses.LogStream(e.resultLabels(), List.of(new LokiResponses.LogEntry(e.timestampNanos(), e.line(), e.structuredMetadata())))).toList()), null, List.of());
        });
        var registry = registry(cap, bytes);
        var query = new QueryService(registry, client, clock);
        return new QueryTools(query, new LogPagingService(query, registry, new LogCursorCodec(clock)));
    }
    private tools.jackson.databind.JsonNode wire(CallToolResult result, int cap, int bytes) {
        var response = new ResponseBudget(mapper, registry(cap, bytes)).fit(JSONRPCResponse.result("id🐈", result));
        byte[] serialized = mapper.writeValueAsBytes(response);
        assertTrue(serialized.length + 1 <= bytes);
        assertFalse(new String(serialized, java.nio.charset.StandardCharsets.UTF_8).contains(LogPagingService.DRAFT_META));
        var node = mapper.readTree(serialized).path("result");
        assertFalse(node.path("isError").asBoolean(), node.toString());
        assertEquals(node.path("structuredContent"), mapper.readTree(node.path("content").get(0).path("text").asText()));
        return node.path("structuredContent");
    }
    private static QueryResults.Event event(String time, String label, String line) {
        return new QueryResults.Event(time, Map.of("job", label), line, Map.of("trace", "x"));
    }
    @ParameterizedTest @ValueSource(strings = {"forward", "backward"})
    void pagesPreserveIdenticalDuplicatesAndStreamsWithEqualTimestamps(String direction) {
        source.set(List.of(event("1", "a", "same"), event("1", "b", "other"), event("1", "a", "same"),
                event("2", "a", "later"), event("3", "a", "end")));
        var tools = tools(20, 6000);
        var page = wire(tools.queryLogs("test", "q | line_format `{{.message}}`", "0", "4", direction, 1, List.of("line")), 20, 6000);
        var lines = new ArrayList<String>();
        var times = new ArrayList<String>();
        int attempts = 0;
        while (true) {
            for (var e : page.path("events")) { lines.add(e.path("line").asText()); times.add(e.path("timestampNanos").asText()); }
            assertEquals("0", page.path("queryWindow").path("startNanos").asText());
            if (!page.has("nextCursor")) break;
            assertTrue(++attempts < 10);
            // Change upstream tie/stream order between calls. Local ordering must remain reproducible.
            var reversed = new ArrayList<>(source.get()); Collections.reverse(reversed); source.set(reversed);
            page = wire(tools.continueLogs("test", page.path("nextCursor").asText()), 20, 6000);
        }
        assertEquals(5, lines.size()); assertEquals(2, Collections.frequency(lines, "same"));
        assertEquals(direction.equals("forward") ? List.of("1", "1", "1", "2", "3") : List.of("3", "2", "1", "1", "1"), times);
        assertEquals("END_OF_RESULTS", page.path("continuationUnavailableReason").asText());
    }
    @Test void cursorTracksActuallyEmittedPrefixAfterWireTrimming() {
        source.set(java.util.stream.IntStream.range(1, 16).mapToObj(i -> event("" + i, "s" + i, "🐈".repeat(3000))).toList());
        var tools = tools(30, 6000);
        var page = wire(tools.queryLogs("test", "q", "0", "20", "forward", 15, List.of("line")), 30, 6000);
        assertTrue(page.path("returnedEntries").asInt() < 15);
        var timestamps = new HashSet<String>();
        int attempts = 0;
        while (true) {
            for (var e : page.path("events")) assertTrue(timestamps.add(e.path("timestampNanos").asText()));
            if (!page.has("nextCursor")) break;
            assertTrue(++attempts < 20);
            page = wire(tools.continueLogs("test", page.path("nextCursor").asText()), 30, 6000);
        }
        assertEquals(15, timestamps.size());
    }
    @Test void saturatedBoundaryStopsExplicitlyWithoutSkippingTimestamp() {
        source.set(List.of(event("1", "a", "same"), event("1", "a", "same"), event("1", "a", "same"), event("2", "a", "later")));
        var tools = tools(2, 6000);
        var first = wire(tools.queryLogs("test", "q", "0", "3", "forward", 1, null), 2, 6000);
        var last = wire(tools.continueLogs("test", first.path("nextCursor").asText()), 2, 6000);
        assertEquals("1", last.path("events").get(0).path("timestampNanos").asText());
        assertFalse(last.has("nextCursor"));
        assertEquals("BOUNDARY_SATURATED", last.path("continuationUnavailableReason").asText());
        assertEquals("UNKNOWN", last.path("completeness").asText());
    }
    @Test void changedOverlapFailsBeforeReturningAnyReplacement() {
        source.set(List.of(event("1", "a", "first"), event("2", "a", "second")));
        var tools = tools(20, 6000);
        var first = wire(tools.queryLogs("test", "q", "0", "3", "forward", 1, List.of()), 20, 6000);
        source.set(List.of(event("1", "a", "CHANGED HIDDEN FIELD"), event("2", "a", "second")));
        var error = assertThrows(LokiOperationException.class, () -> tools.continueLogs("test", first.path("nextCursor").asText()));
        assertEquals(ErrorCode.CURSOR_STALE, error.error().code());
    }
    @Test void relativeWindowIsFixedBeforeContinuation() {
        source.set(List.of(event("99000000000", "a", "a"), event("99500000000", "a", "b")));
        var tools = tools(20, 6000);
        var first = wire(tools.queryLogs("test", "q", "now-1s", "now", "forward", 1, null), 20, 6000);
        wire(tools.continueLogs("test", first.path("nextCursor").asText()), 20, 6000);
        verify(client, times(2)).queryRange(eq("test"), eq("q"), eq(Instant.ofEpochSecond(99)), eq(Instant.ofEpochSecond(100)), eq(20), any(), isNull());
    }
    @Test void codecIsConfidentialBoundToConnectionAndProcessAndExpires() {
        var now = new AtomicReference<>(Instant.ofEpochSecond(100));
        var adjustable = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        var codec = new LogCursorCodec(adjustable);
        var state = new LogCursorCodec.State("test", "SECRET query", "0", "3", "forward", 1, List.of(), codec.expiry(), "1", 1, "digest");
        String token = codec.encode(state);
        assertFalse(new String(Base64.getUrlDecoder().decode(token), java.nio.charset.StandardCharsets.UTF_8).contains("SECRET"));
        assertEquals(state, codec.decode("test", token));
        assertThrows(LokiOperationException.class, () -> codec.decode("other", token));
        assertThrows(LokiOperationException.class, () -> new LogCursorCodec().decode("test", token));
        assertThrows(LokiOperationException.class, () -> codec.decode("test", "!" + token));
        int middle = token.length() / 2;
        String tampered = token.substring(0, middle) + (token.charAt(middle) == 'A' ? 'B' : 'A') + token.substring(middle + 1);
        assertThrows(LokiOperationException.class, () -> codec.decode("test", tampered));
        now.set(Instant.ofEpochSecond(state.expiresAt()));
        assertEquals(ErrorCode.CURSOR_EXPIRED, assertThrows(LokiOperationException.class, () -> codec.decode("test", token)).error().code());
    }
    @Test void longQueryCannotCreateUnboundedCursorAndEmptyPageHasNoCursor() {
        source.set(List.of(event("1", "a", "a"), event("2", "a", "b")));
        var tools = tools(20, 6000);
        var page = wire(tools.queryLogs("test", "x".repeat(30000), "0", "3", "forward", 1, null), 20, 6000);
        assertFalse(page.has("nextCursor"));
        assertEquals("CURSOR_STATE_TOO_LARGE", page.path("continuationUnavailableReason").asText());
        source.set(List.of());
        page = wire(tools.queryLogs("test", "q", "0", "3", "forward", 1, null), 20, 6000);
        assertFalse(page.has("nextCursor"));
        assertEquals("END_OF_RESULTS", page.path("continuationUnavailableReason").asText());
    }
}
