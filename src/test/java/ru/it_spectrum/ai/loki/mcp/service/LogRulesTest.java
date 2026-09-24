package ru.it_spectrum.ai.loki.mcp.service;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.connection.*;
import ru.it_spectrum.ai.loki.mcp.model.LogEvent;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ru.it_spectrum.ai.loki.mcp.client.LokiResponses.*;

/**
 * The asva2 starting catalogue (examples/asva2-rules.json) against the real DEV lines of the fixture.
 */
class LogRulesTest {
    private static final List<String> PACKAGES = List.of("ru.it_spectrum.asv", "ru.it_spectrum.core");
    private final List<LogRule> rules = ConnectionsLoader.loadRules(Path.of("examples/asva2-rules.json"));
    private final List<LogEvent> events = Fixtures.events("asva2-dev-errors.jsonl");
    private final EventNormalizer normalizer = new EventNormalizer();

    private LogRules.Match match(String text) {
        var view = normalizer.view(Fixtures.containing(events, text), List.of("applicationName"));
        return LogRules.match(rules, view, LogSummary.signature(view, PACKAGES, List.of()));
    }

    @Test
    void catalogueClassifiesTheLinesOfTheStand() {
        assertEquals("client-abort", match("Path: /api/systemGrid/getByCurrentUser").rule().id());
        assertEquals(LogRule.Category.NOISE, match("Path: /api/systemGrid/getByCurrentUser").category());
        assertEquals("missing-endpoint", match("No static resource api/selectedDateTime").rule().id());
        assertEquals("bad-request-target", match("Error parsing HTTP request header").rule().id());
        // The recipient is in a wrapper message of the stack line and in the message of the line without a stack.
        var smev = match("check_dul.CheckDulAction action failed");
        assertEquals("smev-recipient", smev.rule().id());
        assertEquals("[dependency: SMEV]", smev.tag());
        assertTrue(smev.advice().startsWith("The request to МВД России through SMEV failed"), smev.advice());
        assertEquals("smev-recipient", match("Ошибка при отправке запроса: Карточка запроса ФОИВ").rule().id());
        var flyway = match("Schema \\\"sbp\\\" has version");
        assertEquals("[startup: schema sbp]", flyway.tag());
        assertTrue(flyway.advice().contains("schema sbp is at version 1.7,"), flyway.advice());
        var task = match("[TASK_EXECUTE_ERROR] Ошибка при выполнении задачи: taskExecutionId=500001");
        assertEquals("[configuration: task worker]", task.tag());
        assertTrue(task.advice().contains("ru.it_spectrum.asv.ssj.bc.tasks.UploadInsuranceCompany"), task.advice());
        // Bugs of the application stay unclassified: the rules name causes outside the code, not every exception.
        assertNull(match("LiqBankDirGenDTOHooksBean"));
        assertNull(match("pk_doc_type"));
    }

    @Test
    void placeholdersFillFromNamedGroupsAndFirstRuleWins() {
        var neighbour = rules.stream().filter(r -> r.id().equals("neighbour-io")).findFirst().orElseThrow();
        var view = new EventNormalizer.View(EventNormalizer.Format.JSON, "ERROR", "ssj", null, "Failed to load banks", null,
                null, Map.of());
        var signature = ErrorSignature.of("""
                org.springframework.web.client.ResourceAccessException: I/O error on GET request for "http://nsi-backend:8611/api/banks": Connection refused
                \tat ru.it_spectrum.asv.ssj.client.NsiClient.load(NsiClient.java:40)
                Caused by: java.net.ConnectException: Connection refused
                \tat java.base/sun.nio.ch.Net.pollConnect(Native Method)
                """, PACKAGES);
        var match = LogRules.match(List.of(neighbour), view, signature);
        assertEquals("[dependency: nsi-backend]", match.tag());
        assertEquals("The call to nsi-backend did not get an answer (connection refused or timed out): that service is down or restarting.", match.advice());
        assertNull(LogRules.match(List.of(), view, signature));
        assertNull(LogRules.match(List.of(neighbour), view, null), "an exception rule needs a stack trace");
    }

    @Test
    void summaryListsKnownCausesAndMovesNoiseApartWithFilters() {
        var client = mock(LokiHttpClient.class);
        var definition = new ConnectionDefinition("dev", null, null, URI.create("http://localhost:1"), ConnectionAuth.NONE, null,
                ZoneId.of("Europe/Moscow"), ConnectionLimits.DEFAULTS, List.of("applicationName"), PACKAGES, rules);
        var service = new QueryService(new ConnectionRegistry(List.of(definition)), client,
                Clock.fixed(Instant.parse("2026-09-21T21:00:00Z"), ZoneOffset.UTC));
        // The fixture plus a crowd of missing-endpoint lines, as on the stand: noise fills most of a cut sample.
        var entries = new ArrayList<LogEntry>();
        for (var event : events) entries.add(new LogEntry(event.timestampNanos(), event.line(), Map.of()));
        String noise = Fixtures.containing(events, "No static resource api/selectedDateTime").line();
        long base = Instant.parse("2026-09-21T20:40:00Z").getEpochSecond() * 1_000_000_000L;
        for (int i = 0; i < 100; i++)
            entries.add(new LogEntry(Long.toString(base + i * 1_000_000_000L), noise, Map.of()));
        entries.sort(java.util.Comparator.comparing((LogEntry e) -> Long.parseLong(e.timestampNanos())).reversed());
        doAnswer(invocation -> {
            Instant end = invocation.getArgument(3);
            int limit = invocation.getArgument(4);
            var page = entries.stream().filter(e -> QueryTime.fromNanos(e.timestampNanos()).isBefore(end)).limit(limit).toList();
            return new QueryResponse(new Streams(List.of(new LogStream(Map.of("app", "asv-app"), page))), new QueryStats(1L), List.of());
        }).when(client).queryRange(anyString(), anyString(), any(), any(), anyInt(), any(), any());

        var text = service.summarize("dev", "{app=\"asv-app\"}", "now-24h", "now", 137);
        assertTrue(text.contains("\nKnown causes by the rules of this connection (lines in the sample):\n"), text);
        assertTrue(text.contains("\n  startup        schema sbp  1 line\n"), text);
        assertTrue(text.contains("\n  dependency     SMEV  "), text);
        assertTrue(text.contains("\n         [dependency: SMEV] The request to МВД России through SMEV failed"), text);
        assertTrue(text.contains("\nNoise by the rules of this connection ("), text);
        assertTrue(text.matches("(?s).*\\n +\\d+×  [0-9:.–]+  sec-ui-backend  missing-endpoint  NoResourceFoundException: No static resource.*"), text);
        assertTrue(text.contains(" sampled lines; to sample past it, add != \"NoResourceFoundException\""), text);
        // Noise is not repeated in the groups above it.
        String groups = text.substring(0, text.indexOf("\nNoise by the rules"));
        assertFalse(groups.contains("NoResourceFoundException: No static resource"), groups);
    }
}
