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

class CorrelationKeysTest {
    private static final List<String> PACKAGES = List.of("ru.it_spectrum.asv", "ru.it_spectrum.core");
    private final List<LogEvent> events = Fixtures.events("asva2-dev-errors.jsonl");
    private final EventNormalizer normalizer = new EventNormalizer();
    private final LokiHttpClient client = mock(LokiHttpClient.class);

    private List<String> keys(String text) {
        return CorrelationKeys.of(normalizer.view(Fixtures.containing(events, text), List.of("applicationName")))
                .stream().map(CorrelationKeys.Key::text).toList();
    }

    private static EventNormalizer.View message(String text) {
        return new EventNormalizer.View(EventNormalizer.Format.PLAIN, null, null, null, text, null, null, Map.of());
    }

    @Test
    void keysAreNameValuePairsWhoseNameEndsInId() {
        assertEquals(List.of("taskExecutionId=500001"), keys("[TASK_EXECUTE_ERROR] Ошибка при выполнении задачи: taskExecutionId=500001"));
        assertEquals(List.of("ErrorID=ERR-00000000-0000-4000-8000-000000000002"), keys("No static resource api/selectedDateTime"));
        assertEquals(List.of("fedExecCommId=500003"), keys("Карточка перешла в ошибочный статус"));
        assertEquals(List.of("objectId=81302"), keys("eventSortId = Правила_проверки_данных_редактирование"));
        assertEquals(List.of(), CorrelationKeys.of(message("valid=true paid: 12345 id=77 userId=-1 orderId=42 requestId=\"a1b2c3\"")).stream()
                .filter(k -> !k.name().equals("requestId")).toList(), "lower-case names, negative and short values are not keys");
        assertEquals(List.of("requestId=a1b2c3"), CorrelationKeys.of(message("requestId=\"a1b2c3\"")).stream().map(CorrelationKeys.Key::text).toList());
        var traced = new EventNormalizer.View(EventNormalizer.Format.JSON, null, null, null, "m", "4bf92f3577b34da6", null, Map.of());
        assertEquals(List.of("traceId=4bf92f3577b34da6"), CorrelationKeys.of(traced).stream().map(CorrelationKeys.Key::text).toList());
        assertTrue(CorrelationKeys.token("13548").matcher("taskExecutionId=13548, status").find());
        assertFalse(CorrelationKeys.token("13548").matcher("taskExecutionId=135480").find());
        assertFalse(CorrelationKeys.token("13548").matcher("ERR-13548x").find());
    }

    @Test
    void summaryLinksTheGroupsOfOneFailureAcrossServices() {
        var groups = LogSummary.group(events, normalizer, List.of("applicationName"), PACKAGES);
        var task = groups.stream().filter(g -> g.lastSignature != null && g.lastSignature.rootMessage().startsWith("Не найдена доступная задача")).findFirst().orElseThrow();
        assertEquals(List.of("taskExecutionId=500001 → scheduler-backend 2 lines"), task.links);
        var lines = LogSummary.render(task, ZoneId.of("Europe/Moscow"));
        assertEquals("         linked: taskExecutionId=500001 → scheduler-backend 2 lines", lines.getLast());
        // A key found in one line only (ErrorID of one request) links nothing.
        var notFound = groups.stream().filter(g -> g.lastSignature != null && g.lastSignature.rootType().equals("NoResourceFoundException")).findFirst().orElseThrow();
        assertTrue(notFound.links.isEmpty());
    }

    private QueryService service(int maxResponseBytes, List<LogEntry> entries) {
        var definition = new ConnectionDefinition("dev", null, null, URI.create("http://localhost:1"), ConnectionAuth.NONE, null,
                ZoneId.of("Europe/Moscow"), new ConnectionLimits(100, 100, 1_000_000, maxResponseBytes, 1000, 86400, 100, 1000),
                List.of("applicationName"), PACKAGES, ConnectionsLoader.loadRules(Path.of("examples/asva2-rules.json")));
        doAnswer(invocation -> {
            int limit = invocation.getArgument(4);
            var page = entries.stream().limit(limit).toList();
            return new QueryResponse(new Streams(List.of(new LogStream(Map.of("namespace", "dev"), page))), new QueryStats(1L), List.of());
        }).when(client).queryRange(anyString(), anyString(), any(), any(), anyInt(), any(), any());
        return new QueryService(new ConnectionRegistry(List.of(definition)), client,
                Clock.fixed(Instant.parse("2026-09-21T22:00:00Z"), ZoneOffset.UTC));
    }

    private List<LogEntry> containing(String value) {
        var entries = new ArrayList<LogEntry>();
        for (var event : events) if (event.line().contains(value)) entries.add(new LogEntry(event.timestampNanos(), event.line(), Map.of()));
        return entries;
    }

    @Test
    void followKeyReadsOneFailureAcrossServicesOldestFirst() {
        var entries = containing("500001");
        // A line holding the value only inside a longer number reaches the server too and is left out.
        entries.add(new LogEntry(Long.toString(Long.parseLong(entries.getLast().timestampNanos()) + 1), "{\"message\":\"batch 5000012 done\",\"log\":{\"level\":\"INFO\"}}", Map.of()));
        var text = service(65536, entries).followKey("dev", "{namespace=\"dev\"}", "taskExecutionId=500001", "now-24h", null, null);
        verify(client).queryRange("dev", "{namespace=\"dev\"} |= \"500001\"", Instant.parse("2026-09-20T22:00:00Z"),
                Instant.parse("2026-09-21T22:00:00Z"), 100, LokiHttpClient.Direction.FORWARD, null);
        assertTrue(text.startsWith("Lines with taskExecutionId=500001 in {namespace=\"dev\"} — dev, 2026-09-21 01:00:00–2026-09-22 01:00:00 (+03:00): "
                + "all 4 lines in 2 services (ssj-backend, scheduler-backend); first error 00:00:04.760 ssj-backend. "
                + "1 line holding 500001 only inside a longer word were left out.\n"), text);
        var body = text.lines().toList();
        assertTrue(body.get(1).startsWith("00:00:04.760 ERROR ssj-backend  [TASK_EXECUTE_ERROR] "), body.get(1));
        assertEquals("         SpectrumException: Не найдена доступная задача с классом ru.it_spectrum.asv.ssj.bc.tasks.UploadInsuranceCompany", body.get(2));
        assertTrue(body.get(3).startsWith("         at ru.it_spectrum.asv.bc.task.runtime.TaskServiceImpl.findDelegate("), body.get(3));
        assertTrue(body.get(4).startsWith("         [configuration: task worker] "), body.get(4));
        assertTrue(text.contains("\n00:00:04.768 WARN  scheduler-backend  [TASK_FAILED_STATUS] "), text);
        // The advice is printed once; the next lines of the same rule carry the tag only.
        assertEquals(1, text.split("routed to the wrong service", -1).length - 1, text);
        assertTrue(text.contains("\n         [configuration: task worker]\n"), text);
        assertFalse(text.contains("5000012"), text);
        assertTrue(text.endsWith("\nShown every line of the window with this key."), text);
    }

    @Test
    void followKeyCutsTheNewestLinesUnderTheBudgetAndValidates() {
        var entries = containing("500001");
        var text = service(1600, entries).followKey("dev", "{namespace=\"dev\"}", "500001", "now-24h", null, null);
        assertTrue(LogText.bytes(text) <= 1600 - LogText.ENVELOPE_BYTES, text);
        assertTrue(text.contains("\n00:00:04.760 ERROR ssj-backend  "), text);
        assertTrue(text.contains("Output limit reached: showing the oldest "), text);
        assertTrue(text.contains(" lines. Newer: repeat with start=\"2026-09-21T00:00:04."), text);
        var empty = service(65536, List.of()).followKey("dev", "{namespace=\"dev\"}", "ErrorID: ERR-1", "now-1h", null, null);
        assertTrue(empty.startsWith("Lines with ErrorID=ERR-1 in {namespace=\"dev\"} — dev, ") && empty.contains(": no lines.\nNo line of the window holds this value."), empty);
        var service = service(65536, List.of());
        assertTrue(assertThrows(LokiOperationException.class, () -> service.followKey("dev", "{namespace=\"dev\"} |= \"x\"", "abc", null, null, null))
                .getMessage().contains("stream selector only"));
        assertTrue(assertThrows(LokiOperationException.class, () -> service.followKey("dev", "{namespace=\"dev\"}", "id=1", null, null, null))
                .getMessage().contains("3 to 200 characters"));
        assertThrows(LokiOperationException.class, () -> service.followKey("dev", "{namespace=\"dev\"}", "a\"b\"c", null, null, null));
        assertThrows(LokiOperationException.class, () -> service.followKey("dev", "{namespace=\"dev\"}", " ", null, null, null));
        assertThrows(LokiOperationException.class, () -> service.followKey("dev", "{namespace=\"dev\"}", "abc", null, null, 1001));
    }
}
