package ru.it_spectrum.ai.loki.mcp.service;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.client.LokiResponses.LabelResponse;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionAuth;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionDefinition;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionLimits;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

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
        String names = service.discover("one", null, null, null);
        assertTrue(names.startsWith("Labels — one, 2026-09-13 11:00:00–12:00:00 (Z): 3.\napp\nlevel\npod"), names);
        when(client.labelValues("one", "app", now.minusSeconds(1), now))
                .thenReturn(new LabelResponse(List.of("frontend", "backend")));
        String values = service.discover("one", "now-1s", "now", "app");
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
        String text = service.discover("tight", "now-1s", "now", "pod");
        assertTrue(LogText.bytes(text) <= 1024 - LogText.ENVELOPE_BYTES);
        assertTrue(text.contains("more; only the first values are shown"));
    }

    @Test
    void rejectsInvalidArgumentsBeforeNetwork() {
        assertThrows(LokiOperationException.class, () -> service.discover("one", null, null, "bad-name"));
        assertThrows(LokiOperationException.class, () -> service.discover("tight", "now-11s", "now", null));
        assertThrows(LokiOperationException.class, () -> service.discover("missing", null, null, null));
        verifyNoInteractions(client);
    }
}
