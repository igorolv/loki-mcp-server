package ru.it_spectrum.ai.loki.mcp.service;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.client.LokiResponses.LabelResponse;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionAuth;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionDefinition;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionLimits;
import ru.it_spectrum.ai.loki.mcp.connection.ServiceSystem;

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

    /**
     * ССЖ by its Spring services (one stand) and by its helm release (the other), НСИ by one service.
     */
    private static ConnectionDefinition withSystems() {
        return new ConnectionDefinition("dev", null, null, URI.create("http://localhost:1"), ConnectionAuth.NONE, null, ZoneId.of("UTC"),
                ConnectionLimits.DEFAULTS, List.of("applicationName", "instance"), List.of(), List.of(), SCOPE, Map.of(), List.of(), List.of(),
                List.of(), ConnectionDefinition.DEFAULT_VERSION_FIELDS,
                List.of(new ServiceSystem(List.of("ССЖ", "ssj"), "deposit insurance", List.of("ssj-backend", "ssj-ui-backend", "ssj-main")),
                        new ServiceSystem(List.of("НСИ"), null, List.of("nsi-backend"))));
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
        assertEquals("{applicationName=\"sec-backend\"} |~ \"ERROR|FATAL\"", resolve(definition(null, Map.of()), null, "sec-backend", "error", null));
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
    void aSystemNameStandsForTheServicesTheWindowHolds() {
        values("applicationName", "ssj-backend", "sec-backend");
        values("instance", "ssj-main", "sec-main");
        var dev = withSystems();
        // ssj-ui-backend logged nothing in the window: left out; the name is matched without regard to case.
        assertEquals(SCOPE.replace("}", ", applicationName=\"ssj-backend\"}"), resolve(dev, null, "ссж", null, null));
        assertEquals(SCOPE.replace("}", ", applicationName=~\"ssj-backend|sec-backend\"}"), resolve(dev, null, "SSJ, sec-backend", null, null));
        // Every name must be on the chosen label: ССЖ and sec-main meet on the releases, not on the Spring names.
        assertEquals(SCOPE.replace("}", ", instance=~\"ssj-main|sec-main\"}"), resolve(dev, null, "ССЖ,sec-main", null, null));
        // A stand without the Spring names: the release holds ССЖ.
        values("applicationName");
        assertEquals(SCOPE.replace("}", ", instance=\"ssj-main\"}"), resolve(dev, null, "ССЖ", null, null));
        assertEquals("No service of НСИ (nsi-backend) in this window (labels applicationName, instance were searched). Try a wider window.",
                message(() -> resolve(dev, null, "НСИ", null, null)));
        assertThrows(LokiOperationException.class, () -> new ServiceSystem(List.of("a,b"), null, List.of("x")));
        assertThrows(LokiOperationException.class, () -> new ServiceSystem(List.of("a"), null, List.of()));
        assertThrows(LokiOperationException.class, () -> new ConnectionDefinition("dev", null, null, URI.create("http://localhost:1"),
                ConnectionAuth.NONE, null, ZoneId.of("UTC"), ConnectionLimits.DEFAULTS, List.of("app"), List.of(), List.of(), null, Map.of(),
                List.of(), List.of(), List.of(), ConnectionDefinition.DEFAULT_VERSION_FIELDS,
                List.of(new ServiceSystem(List.of("ssj"), null, List.of("a")), new ServiceSystem(List.of("SSJ"), null, List.of("b")))));
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
