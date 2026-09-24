package ru.it_spectrum.ai.loki.mcp.service;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.client.LokiResponses.*;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionAuth;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionDefinition;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionLimits;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The reason of an empty result, from label values, labels and series of a stubbed Loki.
 */
class SelectorCheckTest {
    private static final QueryTime.Range WINDOW = new QueryTime.Range(Instant.parse("2026-09-24T07:00:00Z"), Instant.parse("2026-09-24T11:00:00Z"));
    private final LokiHttpClient client = mock(LokiHttpClient.class);
    private final SelectorCheck check = new SelectorCheck(client);

    private void values(String label, String... values) {
        when(client.labelValues(eq("dev"), eq(label), any(), any(), isNull())).thenReturn(new LabelResponse(List.of(values), List.of()));
    }

    private void series(int streams) {
        var list = new java.util.ArrayList<Map<String, String>>();
        for (int i = 0; i < streams; i++) list.add(Map.of("instance", "s" + i));
        when(client.series(eq("dev"), anyList(), any(), any())).thenReturn(new SeriesResponse(list, List.of()));
    }

    @Test
    void aValueNoStreamHasGetsTheClosestOnes() {
        values("namespace", "dev", "tst");
        values("instance", "ssj-main", "ssj-pr-1396", "sec-main", "nsi-main", "scheduler-main", "sbp-main", "audit-main");
        assertEquals("Why: no stream has instance=\"ssj-mian\" in this window; closest values of instance: ssj-main, nsi-main, sbp-main, sec-main, "
                        + "audit-main. discoverLogs with label=\"instance\" lists them all.",
                check.explain("dev", "{namespace=\"dev\", instance=\"ssj-mian\"} |= \"ERROR\"", WINDOW));
        // A part of a value comes first.
        assertEquals(List.of("ssj-main", "ssj-pr-1396", "sec-main"), SelectorCheck.closest("ssj", List.of("sec-main", "ssj-pr-1396", "ssj-main")));
        verify(client, never()).series(any(), anyList(), any(), any());
    }

    @Test
    void aLabelThatDoesNotExistListsTheLabels() {
        values("applicationNme");
        when(client.labels(eq("dev"), any(), any(), isNull())).thenReturn(new LabelResponse(List.of("namespace", "app", "applicationName"), List.of()));
        assertEquals("Why: label applicationNme does not exist in this window; labels: app, applicationName, namespace. "
                + "Take labels and values from discoverLogs.", check.explain("dev", "{applicationNme=\"ssj-backend\"}", WINDOW));
    }

    @Test
    void valuesThatExistAloneButNotTogetherOrAFilterThatDropsEveryLine() {
        values("namespace", "dev", "tst");
        values("instance", "ssj-main");
        series(0);
        assertTrue(check.explain("dev", "{namespace=\"tst\", instance=\"ssj-main\"}", WINDOW)
                .startsWith("Why: no stream matches {namespace=\"tst\", instance=\"ssj-main\"} in this window, although every single value exists"));
        series(14);
        assertEquals("Why: {namespace=\"dev\", instance=~\"ssj.*\"} matches 14 streams, but |~ \"EROR\" left no line. Check the filter text "
                        + "(it is case-sensitive; |~ \"(?i)...\" ignores case); countLogs with {namespace=\"dev\", instance=~\"ssj.*\"} alone shows "
                        + "how many lines the streams have.",
                check.explain("dev", "{namespace=\"dev\", instance=~\"ssj.*\"} |~ \"EROR\"", WINDOW));
        // Escaped quotes in a value are compared unescaped.
        assertEquals(List.of(new SelectorCheck.Label("a", "=", "x\"y")), SelectorCheck.matchers("{a=\"x\\\"y\"} |= \"z\""));
    }

    @Test
    void aFailedLookupLeavesThePlainAnswer() {
        when(client.labelValues(any(), any(), any(), any(), any())).thenThrow(Errors.failure(ErrorCode.UPSTREAM_TIMEOUT, "timeout"));
        assertNull(check.explain("dev", "{a=\"b\"}", WINDOW));
        assertNull(check.explain("dev", "sum(rate({a=\"b\"}[1m]))", WINDOW));
    }

    @Test
    void emptyResultsOfTheToolsSayWhy() {
        values("app", "backend", "frontend");
        doAnswer(invocation -> invocation.getArgument(1).toString().startsWith("{") && invocation.getArgument(6) == null
                ? new QueryResponse(new Streams(List.of()), new QueryStats(0L), List.of()) : null)
                .when(client).queryRange(anyString(), anyString(), any(), any(), anyInt(), any(), any());
        when(client.queryInstant(anyString(), anyString(), any())).thenReturn(new QueryResponse(new Vector(List.of()), new QueryStats(0L), List.of()));
        var registry = new ConnectionRegistry(List.of(new ConnectionDefinition("dev", null, null, URI.create("http://localhost:1"),
                ConnectionAuth.NONE, null, ZoneId.of("UTC"), ConnectionLimits.DEFAULTS, List.of("app"), List.of())));
        var service = new QueryService(registry, client, Clock.fixed(WINDOW.end(), ZoneOffset.UTC));
        String why = "\nWhy: no stream has app=\"bakend\" in this window; closest values of app: backend, frontend. "
                + "discoverLogs with label=\"app\" lists them all.";
        assertTrue(service.logs("dev", "{app=\"bakend\"}", null, null, null, null).endsWith(why));
        assertTrue(service.summarize("dev", "{app=\"bakend\"}", null, null, null).endsWith(why));
        assertTrue(service.count("dev", "{app=\"bakend\"}", null, null, null).endsWith(why));
        assertTrue(service.context("dev", "{app=\"bakend\"}", "10:00:00", null, null).endsWith(why));
    }
}
