package ru.it_spectrum.ai.loki.mcp.service;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionAuth;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionDefinition;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionLimits;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ru.it_spectrum.ai.loki.mcp.client.LokiResponses.*;

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

    private void entries(Map<String, String> labels, List<LogEntry> entries) {
        when(client.queryRange(anyString(), anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new QueryResponse(new Streams(List.of(new LogStream(labels, entries))), null, List.of()));
    }

    private LogEntry entry(int secondsAgo, String line) {
        return new LogEntry(QueryTime.nanos(now.minusSeconds(secondsAgo)), line, Map.of());
    }

    @Test
    void selectorScopeListsLabelsFormatsFieldsLevelsExampleAndNextStep() {
        series(List.of(Map.of("job", "test", "applicationName", "backend", "level", "error"),
                Map.of("job", "test", "applicationName", "frontend", "level", "info")));
        entries(Map.of("job", "test", "applicationName", "backend"), List.of(
                entry(1, "{\"service\":{\"name\":\"backend\"},\"log\":{\"level\":\"ERROR\"},\"message\":\"Ошибка 🐈\",\"traceId\":\"t\"}"),
                entry(2, "plain WARN text"), entry(3, "{\"message\":\"m\",\"log\":{\"level\":\"INFO\"}}")));
        var text = service.discover("one", SELECTOR, "now-1s", "now", null);
        assertEquals("""
                Streams matching {job="test"} in 2026-09-13 11:59:59–12:00:00 (Z): 2.
                Labels:
                  applicationName: backend, frontend
                  job: test
                  level: error, info
                Line format (3 newest lines sampled): JSON 2, plain text 1.
                Levels seen: ERROR, INFO, WARN.
                JSON fields (after | json): log_level, message, service_name, traceId.
                Example line: {"service":{"name":"backend"},"log":{"level":"ERROR"},"message":"Ошибка 🐈","traceId":"t"}
                Next: use countLogs or queryLogs with a selector like {job="test", applicationName="backend"}; filter JSON fields with | json, e.g. | json | log_level=~"(?i)error"; filter text with |= "substring".""", text);
        verify(client).series("one", List.of(SELECTOR), now.minusSeconds(1), now);
        verify(client).queryRange("one", SELECTOR, now.minusSeconds(1), now, 20, LokiHttpClient.Direction.BACKWARD, null);
        verifyNoMoreInteractions(client);
    }

    @Test
    void withoutSelectorLabelsComeFromLabelEndpointsAndSampleUsesAServiceLabel() {
        when(client.labels("one", now.minusSeconds(3600), now, null)).thenReturn(new LabelResponse(List.of("pod", "app", "level"), List.of()));
        when(client.labelValues(eq("one"), eq("app"), any(), any(), isNull())).thenReturn(new LabelResponse(List.of("b", "a"), List.of()));
        when(client.labelValues(eq("one"), eq("level"), any(), any(), isNull())).thenReturn(new LabelResponse(List.of("error", "info"), List.of()));
        var pods = new ArrayList<String>();
        for (int i = 0; i < 55; i++) pods.add("pod-" + i);
        when(client.labelValues(eq("one"), eq("pod"), any(), any(), isNull())).thenReturn(new LabelResponse(pods, List.of()));
        entries(Map.of("app", "a", "level", "info"), List.of(entry(1, "hello")));
        var text = service.discover("one", null, null, null, null);
        assertTrue(text.startsWith("Labels in 2026-09-13 11:00:00–12:00:00 (Z) (one): 3.\nLabels:\n  app: a, b\n  level: error, info\n  pod: 55 distinct values (high cardinality, not listed)\n"), text);
        assertTrue(text.contains("Line format (1 newest lines sampled): plain text 1.\nLevels seen: INFO.\nExample line: hello\n"), text);
        assertTrue(text.endsWith("Next: use countLogs or queryLogs with a selector like {app=\"a\"}; filter by the level label, e.g. {..., level=\"error\"}; filter text with |= \"substring\"."), text);
        verify(client).queryRange("one", "{app=~\".+\"}", now.minusSeconds(3600), now, 20, LokiHttpClient.Direction.BACKWARD, null);
    }

    @Test
    void valueListsAreCappedAndSampleFailuresDoNotHideLabels() {
        var values = new ArrayList<String>();
        for (int i = 0; i < 15; i++) values.add("v" + String.format("%02d", i));
        var streams = new ArrayList<Map<String, String>>();
        for (String v : values) streams.add(Map.of("job", v));
        series(streams);
        when(client.queryRange(anyString(), anyString(), any(), any(), anyInt(), any(), any())).thenThrow(Errors.failure(ErrorCode.UPSTREAM_TIMEOUT, "SECRET"));
        var text = service.discover("one", SELECTOR, "now-1s", "now", null);
        assertTrue(text.contains("  job: v00, v01, v02, v03, v04, v05, v06, v07, v08, v09 (+5 more: discoverLogs with label=\"job\")\n"), text);
        assertTrue(text.contains("No lines sampled in this window; fields are unknown."), text);
        assertFalse(text.contains("SECRET"));
        assertTrue(text.contains("Next: use countLogs or queryLogs with a selector like {job=\"test\"}; filter text"), text);
    }

    @Test
    void validatesSelectorsWindowsAndConnectionsBeforeNetwork() {
        for (String selector : List.of("{}", "sum(rate({job=\"x\"}[1m]))", "{job=\"x\"} | json", "{job=\"x\"} |= \"foo\"", "{job=\"x\"} garbage"))
            assertTrue(assertThrows(LokiOperationException.class, () -> service.discover("one", selector, "now-1s", "now", null)).getMessage().contains("stream selector"));
        assertThrows(LokiOperationException.class, () -> service.discover(null, SELECTOR, "now-1s", "now", null));
        assertThrows(LokiOperationException.class, () -> service.discover("missing", SELECTOR, "now-1s", "now", null));
        assertThrows(LokiOperationException.class, () -> service.discover("two", SELECTOR, "now-11s", "now", null));
        assertThrows(LokiOperationException.class, () -> service.discover("one", "{job=\"" + "x".repeat(9000) + "\"}", "now-1s", "now", null));
        verifyNoInteractions(client);
    }

    @Test
    void matcherQuotedBracesCommasAndEscapesAreNotPipelines() {
        series(List.of());
        entries(Map.of(), List.of());
        for (String selector : List.of("{job=~\"a|b\", pod!=\"x\"}", "{job=\"a}b,c\\\"d\"}", "{job!~\"foo\"}"))
            assertTrue(service.discover("one", selector, "now-1s", "now", null).startsWith("Streams matching " + selector));
        assertTrue(service.discover("one", SELECTOR, "now-1s", "now", null).contains("No labels found in this window."));
    }

    @Test
    void cancellationStopsFurtherCallsAndSmallBudgetsStillFit() {
        when(client.series(anyString(), anyList(), any(), any())).thenThrow(Errors.failure(ErrorCode.OPERATION_CANCELLED, "safe"));
        assertThrows(LokiOperationException.class, () -> service.discover("one", SELECTOR, "now-1s", "now", null));
        verify(client).series(eq("one"), anyList(), any(), any());
        verifyNoMoreInteractions(client);
        reset(client);
        series(List.of(Map.of("job", "test")));
        entries(Map.of("job", "test"), List.of(entry(1, "{\"message\":\"" + "x".repeat(2000) + "\"}")));
        var text = service.discover("two", SELECTOR, "now-1s", "now", null);
        assertTrue(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024 - LogText.ENVELOPE_BYTES, text);
        assertTrue(text.contains("Example line: {\"message\":\"xxx"), text);
    }

    @Test
    void labelModeListsEveryValueAlphabeticallyAndNothingElse() {
        when(client.labelValues("one", "applicationName", now.minusSeconds(3600), now, null)).thenReturn(new LabelResponse(List.of("nsi", "ssj", "auth"), List.of()));
        assertEquals("""
                Values of applicationName, 2026-09-13 11:00:00–12:00:00 (Z) (one): 3.
                auth
                nsi
                ssj""", service.discover("one", null, null, null, "applicationName"));
        verify(client).labelValues("one", "applicationName", now.minusSeconds(3600), now, null);
        verifyNoMoreInteractions(client);
        when(client.labelValues("one", "applicationName", now.minusSeconds(1), now, SELECTOR)).thenReturn(new LabelResponse(List.of(), List.of()));
        assertTrue(service.discover("one", SELECTOR, "now-1s", "now", " applicationName ").startsWith(
                "Values of applicationName in streams matching {job=\"test\"}, 2026-09-13 11:59:59–12:00:00 (Z) (one): 0.\nNo values in this window;"));
        var many = new ArrayList<String>();
        for (int i = 0; i < 250; i++) many.add(String.format("v%03d", i));
        when(client.labelValues("one", "pod", now.minusSeconds(3600), now, null)).thenReturn(new LabelResponse(many, List.of()));
        var capped = service.discover("one", null, null, null, "pod");
        assertEquals(202, capped.lines().count(), capped);
        assertTrue(capped.endsWith("v199\n(+50 more; narrow with selector)"), capped);
        when(client.labelValues("two", "pod", now.minusSeconds(1), now, null)).thenReturn(new LabelResponse(many, List.of()));
        var small = service.discover("two", null, "now-1s", "now", "pod");
        assertTrue(small.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024 - LogText.ENVELOPE_BYTES, small);
        assertTrue(small.startsWith("Values of pod, ") && small.contains("\nv000\n") && small.contains(" more; narrow with selector)"), small);
        assertTrue(assertThrows(LokiOperationException.class, () -> service.discover("one", null, null, null, "bad-name")).getMessage().contains("label name"));
        assertTrue(assertThrows(LokiOperationException.class, () -> service.discover("one", "{job=\"x\"} |= \"a\"", null, null, "pod")).getMessage().contains("stream selector"));
    }
}
