package ru.it_spectrum.ai.loki.mcp.service;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.client.LokiResponses.LabelResponse;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionAuth;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionDefinition;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionLimits;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Queries built from service, level and text.
 */
class QueryIntentTest {
    private static final QueryTime.Range WINDOW = new QueryTime.Range(Instant.parse("2026-09-24T07:00:00Z"), Instant.parse("2026-09-24T11:00:00Z"));
    private static final String SCOPE = "{namespace=\"dev\", app=~\"asv-app|sp-app\"}";
    private final LokiHttpClient client = mock(LokiHttpClient.class);

    private static ConnectionDefinition definition(String scope, Map<String, String> levels) {
        return new ConnectionDefinition("dev", null, null, URI.create("http://localhost:1"), ConnectionAuth.NONE, null, ZoneId.of("UTC"),
                ConnectionLimits.DEFAULTS, List.of("applicationName", "instance"), List.of(), List.of(), scope, levels);
    }

    private static String message(Runnable call) {
        var failure = assertThrows(LokiOperationException.class, call::run);
        return failure.error().message();
    }

    private void values(String label, String... values) {
        when(client.labelValues(eq("dev"), eq(label), any(), any(), any())).thenReturn(new LabelResponse(List.of(values), List.of()));
    }

    private String resolve(ConnectionDefinition definition, String query, String service, String level, String text) {
        return QueryIntent.resolve(definition, client, query, service, level, text, WINDOW);
    }

    @Test
    void theServiceIsLookedUpAmongTheServiceLabelsWithinTheScope() {
        values("applicationName", "ssj-backend", "sec-backend");
        values("instance", "ssj-main", "sec-main", "ssj-pr-1.2");
        var dev = definition(SCOPE, Map.of("error", "|~ \"ERROR|Exception|Caused by\""));
        assertEquals("{namespace=\"dev\", app=~\"asv-app|sp-app\", applicationName=\"ssj-backend\"} |~ \"ERROR|Exception|Caused by\" |= \"time \\\"out\\\"\"",
                resolve(dev, null, "ssj-backend", "ERROR", "time \"out\""));
        verify(client).labelValues(eq("dev"), eq("applicationName"), any(), any(), eq(SCOPE));
        // Several services of the second label: a regular expression with the values escaped.
        assertEquals("{namespace=\"dev\", app=~\"asv-app|sp-app\", instance=~\"ssj-main|ssj-pr-1\\\\.2\"}", resolve(dev, null, " ssj-main, ssj-pr-1.2", null, ""));
        // The scope alone with a level of the defaults; a given query passes unchanged.
        assertEquals(SCOPE + " |~ \"WARN\"", resolve(dev, null, null, "warn", null));
        assertEquals("{a=\"b\"}", resolve(dev, "{a=\"b\"}", null, null, " "));
        // Without a scope a service is the selector.
        assertEquals("{applicationName=\"sec-backend\"} |~ \"ERROR|FATAL|Exception|Caused by\"", resolve(definition(null, Map.of()), null, "sec-backend", "error", null));
    }

    @Test
    void mistakesAreArgumentErrorsWithWhatToDo() {
        values("applicationName", "ssj-backend", "sec-backend");
        values("instance", "ssj-main", "sec-main");
        var dev = definition(SCOPE, Map.of());
        assertEquals("No service \"ssj-bakend\" in this window (labels applicationName, instance were searched); closest: ssj-backend, "
                + "sec-backend, ssj-main, sec-main.", message(() -> resolve(dev, null, "ssj-bakend", null, null)));
        assertEquals("These services are named by different labels (applicationName, instance); ask for them one call at a time.",
                message(() -> resolve(dev, null, "ssj-backend,ssj-main", null, null)));
        assertTrue(message(() -> resolve(dev, "{a=\"b\"}", "ssj-backend", null, null)).startsWith("Pass either query or service/level/text, not both."));
        assertEquals("level must be one of: error, warn.", message(() -> resolve(dev, null, null, "debug", null)));
        assertTrue(message(() -> resolve(definition(null, Map.of()), null, null, "error", null)).startsWith("This connection has no scope"));
        assertTrue(message(() -> resolve(dev, null, "a\"b", null, null)).startsWith("service must be names"));
    }

    @Test
    void profileLevelsExtendTheDefaultsAndBadOnesAreRejected() {
        var dev = definition(SCOPE, Map.of("error", "| detected_level=\"error\"", "fatal", "|= \"FATAL\""));
        assertEquals(Map.of("error", "| detected_level=\"error\"", "fatal", "|= \"FATAL\"", "warn", "|~ \"WARN\""), dev.allLevels());
        for (var levels : List.of(Map.of("Error", "|= \"x\""), Map.of("error", "ERROR"), Map.of("error", "|= \"x\"\n")))
            assertThrows(LokiOperationException.class, () -> definition(SCOPE, levels));
        assertThrows(LokiOperationException.class, () -> definition("{namespace=\"dev\"} |= \"x\"", Map.of()));
        assertThrows(LokiOperationException.class, () -> definition("namespace=\"dev\"", Map.of()));
    }
}
