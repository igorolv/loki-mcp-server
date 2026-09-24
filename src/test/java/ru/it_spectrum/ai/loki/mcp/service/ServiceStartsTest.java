package ru.it_spectrum.ai.loki.mcp.service;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionAuth;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionDefinition;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionLimits;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;
import ru.it_spectrum.ai.loki.mcp.model.LogEvent;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static ru.it_spectrum.ai.loki.mcp.client.LokiResponses.*;

/**
 * Starts, stops and deploys of the asva2 DEV stand (fixtures/asva2-dev-lifecycle.jsonl): every line the Loki side of
 * {@link ServiceStarts#FILTER} passes for five releases over a day, and the Flyway errors logged during the starts.
 */
class ServiceStartsTest {
    private static final List<String> SERVICE_LABELS = List.of("applicationName", "instance", "container");
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    /**
     * The ECS release plus the build number and commit the asva2 images add, as the asva2 profile names them.
     */
    private static final Map<String, String> VERSION_FIELDS = new LinkedHashMap<>();

    static {
        VERSION_FIELDS.put("service.version", "");
        VERSION_FIELDS.put("build.version", "build");
        VERSION_FIELDS.put("git.commit", "commit");
    }

    private final List<LogEvent> events = Fixtures.events("asva2-dev-lifecycle.jsonl");
    private final Instant now = QueryTime.fromNanos(events.getLast().timestampNanos()).plusSeconds(60);
    private final EventNormalizer normalizer = new EventNormalizer();

    private static QueryResponse response(List<LogEvent> lines) {
        var streams = new LinkedHashMap<Map<String, String>, List<LogEntry>>();
        for (var event : lines)
            streams.computeIfAbsent(event.labels(), k -> new ArrayList<>()).add(new LogEntry(event.timestampNanos(), event.line(), Map.of()));
        var result = new ArrayList<LogStream>();
        for (var stream : streams.entrySet()) result.add(new LogStream(stream.getKey(), stream.getValue()));
        return new QueryResponse(new Streams(result), new QueryStats(1L), List.of());
    }

    private List<LogEvent> flyway() {
        return events.stream().filter(e -> e.line().contains("but no migration could be resolved")).toList();
    }

    private QueryService service(LokiHttpClient client, int maxResponseBytes) {
        var registry = new ConnectionRegistry(List.of(new ConnectionDefinition("dev", null, null, URI.create("http://localhost:1"),
                ConnectionAuth.NONE, null, MOSCOW, new ConnectionLimits(100, 100, 8_000_000, maxResponseBytes, 1000, 86400), SERVICE_LABELS,
                List.of(), List.of(), null, Map.of(), List.of(), List.of(), List.of(), VERSION_FIELDS, List.of())));
        return new QueryService(registry, client, Clock.fixed(now, ZoneOffset.UTC));
    }

    /**
     * A mock Loki: the start/stop query gets the whole fixture (the Java side re-checks every line), any other query
     * the Flyway errors.
     */
    private LokiHttpClient loki() {
        var client = mock(LokiHttpClient.class);
        doAnswer(invocation -> response(((String) invocation.getArgument(1)).endsWith(ServiceStarts.FILTER) ? events : flyway()))
                .when(client).queryRange(anyString(), anyString(), any(), any(), anyInt(), any(), any());
        return client;
    }

    @Test
    void summaryShowsRestartsDeploysAndTheErrorsLoggedWhileStarting() {
        var client = loki();
        String text = service(client, 65536).summarize("dev", "{namespace=\"dev\", level=\"error\"} |= \"migration\"", "now-24h", "now", null);
        // The level matcher is dropped: start lines are INFO.
        verify(client).queryRange("dev", "{namespace=\"dev\"}" + ServiceStarts.FILTER, now.minusSeconds(86400), now, 1000,
                LokiHttpClient.Direction.BACKWARD, null);
        assertTrue(text.contains("\nRestarts and deploys in the window (Spring Boot start and graceful stop lines of {namespace=\"dev\"}):\n"
                + "  parus-ui-backend [parus-main]  started 13:14:14.085, 16:04:16.374, 17:09:51.167; version development build LOCAL commit unknown, unchanged; "
                + "3 sampled lines logged while starting\n"
                + "  sbp-ui-backend [sbp-main]  started 13:08:13.827, 15:57:38.749, 16:56:42.055; version development build LOCAL commit unknown, unchanged; "
                + "3 sampled lines logged while starting\n"), text);
        // The wave of Flyway errors reads as a consequence of the restarts.
        assertTrue(text.contains("ERROR sbp-main  Schema \"sbp\" has version 1.7, but no migration could be resolved in the configured locations !\n"
                + "         logged while starting: 3 of 3 lines, newest in the start of sbp-ui-backend [sbp-main] 16:54:24.863–16:56:42.055, "
                + "version development build LOCAL commit unknown\n"), text);
        // Releases with a build number show their deploys: only the changed parts, then the current version.
        assertTrue(text.contains("\n  ssj-backend [ssj-main]  started 12:28:19.155, 13:36:53.053, 16:18:28.254, 17:21:23.368; "
                + "4 deploys, last 2 at 16:18:28.254 build 2790 → 2791, commit c000000009 → c000000007; "
                + "17:21:23.368 build 2791 → 2792, commit c000000007 → c000000008; now main build 2792 commit c000000008\n"), text);
        // Two replicas of one version are one deploy each time, not two.
        assertTrue(text.contains("\n  ssj-ui-backend [ssj-main]  started 8 times, last 16:16:49.198, 16:18:20.493, 17:19:54.109, 17:37:54.076; 4 deploys, "), text);
        // A pod that logged Starting and never Started.
        assertTrue(text.contains("\n  sbp-backend [sbp-main]  started 13:06:46.451, 15:56:59.452, 17:02:33.157; version development build LOCAL commit unknown, unchanged; "
                + "start at 17:00:01.481 did not finish (no \"Started\" line after it in its stream)\n"), text);
        // Services with errors while starting first, then deploys and unfinished starts, then the rest.
        assertTrue(text.indexOf("sms-ui-backend [sms-main]") < text.indexOf("ssj-ui-backend [ssj-main]")
                && text.indexOf("sbp-backend [sbp-main]") < text.indexOf("parus-backend [parus-main]"), text);
        assertFalse(text.contains("HikariPool") || text.contains("Tomcat") || text.contains("Kafka"), text);
    }

    @Test
    void startsPairInOneStreamAndLinesOfOtherStreamsStayOut() {
        var starts = ServiceStarts.of("{namespace=\"dev\"}", events, false, normalizer, SERVICE_LABELS, VERSION_FIELDS, now);
        assertEquals(10, starts.services());
        var sbpUi = flyway().stream().filter(e -> e.line().contains("\\\"sbp\\\"")).toList();
        assertEquals(3, sbpUi.size());
        for (var line : sbpUi) {
            var start = starts.startOf(line);
            assertNotNull(start, line.line());
            assertEquals("sbp-ui-backend [sbp-main]", start.service());
            assertTrue(start.end() != null && start.begin().isBefore(start.end()));
        }
        var other = new LogEvent(sbpUi.getFirst().timestampNanos(), Map.of("instance", "sbp-main", "pod", "another"), "x", Map.of());
        assertNull(starts.startOf(other));
    }

    @Test
    void plainSpringLinesAndStartedWithoutItsStartingLine() {
        var labels = Map.of("app", "billing");
        Instant base = Instant.parse("2026-09-13T10:00:00Z");
        var lines = List.of(
                new LogEvent(QueryTime.nanos(base), Map.of("app", "billing", "pod", "old"),
                        "2026-09-13 10:00:00.000  INFO 1 --- [main] o.s.b.w.e.tomcat.GracefulShutdown : Graceful shutdown complete", Map.of()),
                new LogEvent(QueryTime.nanos(base.plusSeconds(60)), labels,
                        "2026-09-13 10:01:00.000  INFO 1 --- [main] c.e.BillingApplication : Starting BillingApplication v2.4.1 using Java 21.0.4 with PID 1", Map.of()),
                new LogEvent(QueryTime.nanos(base.plusSeconds(90)), labels,
                        "2026-09-13 10:01:30.000  INFO 1 --- [main] c.e.BillingApplication : Started BillingApplication in 29.5 seconds (JVM running for 31.2)", Map.of()),
                new LogEvent(QueryTime.nanos(base.plusSeconds(600)), Map.of("app", "billing", "pod", "next"),
                        "2026-09-13 10:10:00.000  INFO 1 --- [main] c.e.BillingApplication : Started BillingApplication in 20.1 seconds (process running for 22.5)", Map.of()));
        var starts = ServiceStarts.of("{app=\"billing\"}", lines, false, normalizer, List.of("app"), ConnectionDefinition.DEFAULT_VERSION_FIELDS,
                base.plusSeconds(3600));
        assertEquals("""
                Restarts and deploys in the window (Spring Boot start and graceful stop lines of {app="billing"}):
                  billing  started 10:01:30.000, 10:10:00.000; version v2.4.1""", String.join("\n", starts.render(10, ZoneId.of("UTC"))));
        // Starts that print no version at all: listed without one.
        var bare = ServiceStarts.of("{app=\"billing\"}", List.of(lines.get(2), lines.get(3)), false, normalizer, List.of("app"),
                ConnectionDefinition.DEFAULT_VERSION_FIELDS, base.plusSeconds(3600));
        assertEquals("  billing  started 10:01:30.000, 10:10:00.000", bare.render(10, ZoneId.of("UTC")).get(1));
        // Without its Starting line the beginning is the Started time minus the process uptime.
        var inStart = new LogEvent(QueryTime.nanos(base.plusSeconds(590)), Map.of("app", "billing", "pod", "next"), "error", Map.of());
        assertEquals(base.plusSeconds(600).minusMillis(22_500), starts.startOf(inStart).begin());
    }

    @Test
    void selectorKeepsStreamMatchersAndDropsLevel() {
        assertEquals("{namespace=\"dev\", app=~\"asv-app|sp-app\"}",
                ServiceStarts.selector("{namespace=\"dev\", level=\"error\", app=~\"asv-app|sp-app\"} |~ \"ERROR|Exception\" | json"));
        assertEquals("{app=\"x\"}", ServiceStarts.selector(" {app=\"x\",detected_level!=\"info\"}"));
        assertNull(ServiceStarts.selector("{level=\"error\"} |= \"x\""));
        assertNull(ServiceStarts.selector("sum(rate({app=\"x\"}[1m]))"));
    }

    @Test
    void aFailedStartQueryCostsTheBlockNotTheSummary() {
        var client = mock(LokiHttpClient.class);
        doAnswer(invocation -> {
            if (((String) invocation.getArgument(1)).endsWith(ServiceStarts.FILTER))
                throw Errors.failure(ErrorCode.UPSTREAM_TIMEOUT, "timeout");
            return response(flyway());
        }).when(client).queryRange(anyString(), anyString(), any(), any(), anyInt(), any(), any());
        String text = service(client, 65536).summarize("dev", "{namespace=\"dev\"} |= \"migration\"", "now-24h", "now", null);
        assertTrue(text.contains("\nRestarts and deploys: not checked, the query for start and stop lines of {namespace=\"dev\"} failed (UPSTREAM_TIMEOUT).\n"), text);
        assertTrue(text.contains("Schema \"sbp\" has version 1.7"), text);
    }

    @Test
    void theBudgetDropsRestartedServicesBeforeTopGroups() {
        String text = service(loki(), 3600).summarize("dev", "{namespace=\"dev\"} |= \"migration\"", "now-24h", "now", null);
        assertTrue(LogText.bytes(text) <= 3600 - LogText.ENVELOPE_BYTES, text);
        // The cut services are named in one line; the groups stay whole.
        assertTrue(text.contains("\n  (+") && !text.contains("Output limit reached"), text);
        assertEquals(3, text.split("\n +3×  ").length - 1, text);
    }
}
