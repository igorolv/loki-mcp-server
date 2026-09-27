package ru.it_spectrum.ai.loki.mcp.service;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.client.LokiResponses.LabelResponse;
import ru.it_spectrum.ai.loki.mcp.client.LokiResponses.SeriesResponse;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class DiscoveryServiceTest {
    private final LokiHttpClient client = mock(LokiHttpClient.class);
    private final Instant now = Instant.parse("2026-09-13T12:00:00.123456789Z");
    private final ConnectionRegistry registry = new ConnectionRegistry(List.of(
            new ConnectionDefinition("one", null, URI.create("http://localhost:1"), ConnectionAuth.NONE, null,
                    ZoneOffset.UTC, ConnectionLimits.DEFAULTS),
            new ConnectionDefinition("tight", null, URI.create("http://localhost:2"), ConnectionAuth.NONE, null,
                    ZoneOffset.UTC, new ConnectionLimits(100, 100, 10000, 1024, 1, 10))));
    private final DiscoveryService service = new DiscoveryService(registry, client, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void listsNamesAndValuesUsingOnlyMetadataEndpoints() {
        when(client.labels("one", now.minusSeconds(3600), now))
                .thenReturn(new LabelResponse(List.of("pod", "app", "level")));
        String names = service.discover("one", null, null, null, null);
        assertTrue(names.startsWith("Labels — one, 2026-09-13 11:00:00–12:00:00 (Z): 3.\napp\nlevel\npod"), names);
        when(client.labelValues("one", "app", now.minusSeconds(1), now))
                .thenReturn(new LabelResponse(List.of("frontend", "backend")));
        String values = service.discover("one", "now-1s", "now", "app", null);
        assertEquals("Values of app — one, 2026-09-13 11:59:59–12:00:00 (Z): 2.\nbackend\nfrontend", values);
        verify(client).labels("one", now.minusSeconds(3600), now);
        verify(client).labelValues("one", "app", now.minusSeconds(1), now);
        verifyNoMoreInteractions(client);
    }

    @Test
    void trimsLongValueListsToTheResponseBudget() {
        var values = new ArrayList<String>();
        for (int i = 0; i < 250; i++) values.add(String.format("value-%03d", i));
        when(client.labelValues("tight", "pod", now.minusSeconds(1), now))
                .thenReturn(new LabelResponse(values));
        String text = service.discover("tight", "now-1s", "now", "pod", null);
        assertTrue(LogText.bytes(text) <= 1024 - LogText.ENVELOPE_BYTES);
        assertTrue(text.contains("more; only the first values are shown"));
    }

    @Test
    void rejectsInvalidArgumentsBeforeNetwork() {
        assertThrows(LokiOperationException.class, () -> service.discover("one", null, null, "bad-name", null));
        assertThrows(LokiOperationException.class, () -> service.discover("tight", "now-11s", "now", null, null));
        assertThrows(LokiOperationException.class, () -> service.discover("missing", null, null, null, null));
        verifyNoInteractions(client);
    }

    @Test
    void defaultDiscoveryWindowAllowsThreeDays() {
        when(client.labels("one", now.minusSeconds(3 * 86_400), now))
                .thenReturn(new LabelResponse(List.of("app")));
        String text = service.discover("one", "now-3d", "now", null, null);
        assertTrue(text.contains("app"), text);
        verify(client).labels("one", now.minusSeconds(3 * 86_400), now);
    }

    @Test
    void listsCompleteSeriesAndScopedValuesWithoutReadingLines() {
        var response = new SeriesResponse(List.of(
                Map.of("app", "api", "namespace", "prod", "pod", "b"),
                Map.of("app", "api", "namespace", "test", "pod", "c"),
                Map.of("app", "api", "namespace", "prod", "pod", "a")));
        when(client.series("one", "{app=\"api\"}", now.minusSeconds(3600), now)).thenReturn(response);

        String sets = service.discover("one", null, null, null, "{app=\"api\"}");
        assertTrue(sets.startsWith("Series — one, 2026-09-13 11:00:00–12:00:00 (Z): 3 label sets returned by Loki."), sets);
        assertTrue(sets.indexOf("pod=\"a\"") < sets.indexOf("pod=\"b\""), sets);
        assertTrue(sets.contains("{app=\"api\", namespace=\"test\", pod=\"c\"}"), sets);
        assertTrue(sets.contains("Label sets do not count log lines"), sets);

        String values = service.discover("one", null, null, "namespace", "{app=\"api\"}");
        assertTrue(values.startsWith("Values of namespace among series — one"), values);
        assertTrue(values.contains(": 2 values from 3 label sets returned by Loki.\n\"prod\"\n\"test\""), values);
        verify(client, times(2)).series("one", "{app=\"api\"}", now.minusSeconds(3600), now);
        verifyNoMoreInteractions(client);
    }

    @Test
    void seriesOutputEscapesLabelsAndReportsCutsWithoutSplittingSets() {
        var sets = new ArrayList<Map<String, String>>();
        for (int i = 0; i < 80; i++) {
            sets.add(Map.of("app", "api", "pod", String.format("pod-%03d-", i) + "x".repeat(60)));
        }
        sets.add(Map.of("app", "api", "pod", "line\n\"quoted\"\\tail"));
        when(client.series("tight", "{app=\"api\"}", now.minusSeconds(1), now))
                .thenReturn(new SeriesResponse(sets));
        String text = service.discover("tight", "now-1s", "now", null, "{app=\"api\"}");
        assertTrue(LogText.bytes(text) <= 1024 - LogText.ENVELOPE_BYTES, text);
        assertTrue(text.contains("81 label sets returned by Loki"), text);
        assertTrue(text.contains("Output limit reached: showing"), text);
        assertTrue(text.contains("Narrow match or the time window"), text);
        assertTrue(text.lines().filter(line -> line.startsWith("{")).allMatch(line -> line.endsWith("}")), text);
        assertTrue(text.contains("\\n\\\"quoted\\\"\\\\tail") || !text.contains("quoted"), text);
    }

    @Test
    void seriesRequiresNarrowWindowAndCompleteUpstreamBody() {
        assertThrows(LokiOperationException.class,
                () -> service.discover("one", "now-2d", "now", null, "{app=\"api\"}"));
        for (String match : List.of("", "{}", "{   }", "{app=\"api\"} |= \"error\"")) {
            assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(LokiOperationException.class,
                    () -> service.discover("one", null, null, null, match)).error().code());
        }
        verifyNoInteractions(client);

        when(client.series("one", "{app=\"api\"}", now.minusSeconds(3600), now))
                .thenThrow(Errors.failure(ErrorCode.UPSTREAM_RESPONSE_TOO_LARGE, "generic"));
        var failure = assertThrows(LokiOperationException.class,
                () -> service.discover("one", null, null, "pod", "{app=\"api\"}"));
        assertEquals(ErrorCode.UPSTREAM_RESPONSE_TOO_LARGE, failure.error().code());
        assertTrue(failure.error().message().contains("no complete result"));
        assertTrue(failure.error().message().contains("Narrow match or the time window"));
    }
}
