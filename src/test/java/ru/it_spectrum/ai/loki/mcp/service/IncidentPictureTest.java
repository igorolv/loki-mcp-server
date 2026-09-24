package ru.it_spectrum.ai.loki.mcp.service;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionsLoader;
import ru.it_spectrum.ai.loki.mcp.connection.LogRule;
import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;
import ru.it_spectrum.ai.loki.mcp.model.LogEvent;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;

/**
 * The incident picture of summarizeLogs on the asva2-dev-contrast window and on made-up lines.
 */
class IncidentPictureTest {
    private static final String QUERY = "{namespace=\"dev\", app=~\"asv-app|sp-app\"} |~ \"ERROR|Exception|Caused by\"";
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private final EventNormalizer normalizer = new EventNormalizer();
    private final GroupHistoryTest stand = new GroupHistoryTest();
    private final List<LogRule> rules = rules();

    private static List<LogRule> rules() {
        var rules = new ArrayList<>(ConnectionsLoader.loadRules(Path.of("examples/asva2-rules.json")));
        rules.addAll(ConnectionsLoader.loadRules(Path.of("examples/java-rules.json")));
        return rules;
    }

    private static String picture(String text) {
        var lines = text.lines().dropWhile(l -> !l.startsWith("Incident picture")).toList();
        var result = new ArrayList<String>();
        for (String line : lines) {
            if (!result.isEmpty() && !line.matches("(\\d\\. |   |Not in the picture).*")) break;
            result.add(line);
        }
        return String.join("\n", result);
    }

    private static LogEvent line(String instant, String instance, String message, String trace) {
        String json = "{\"log\":{\"level\":\"ERROR\"},\"message\":\"" + message + "\""
                + (trace == null ? "" : ",\"error\":{\"stack_trace\":\"" + trace.replace("\n", "\\n").replace("\t", "\\t") + "\"}") + "}";
        return new LogEvent(QueryTime.nanos(Instant.parse(instant)), Map.of("instance", instance), json, Map.of());
    }

    private static LogEvent start(String instant, String pod, String message) {
        return new LogEvent(QueryTime.nanos(Instant.parse(instant)), Map.of("instance", "orders", "pod", pod), message, Map.of());
    }

    @Test
    void theWindowReadWholeGivesExactOnsetsTheOrderOfServicesAndTheKeyOfOneFailure() {
        var requests = new ArrayList<String>();
        var text = stand.service(stand.stand(requests), 64 * 1024, rules).summarize("dev", QUERY, "now-4h", "now", null);
        String picture = picture(text);
        // The picture comes after the history line and before the groups; noise (the missing endpoint) is not in it.
        assertTrue(text.contains("4 groups new, 1 more than usual, 6 seen before.\nIncident picture: new or growing errors, oldest first"), text);
        assertEquals(4, picture.lines().filter(l -> l.matches("\\d\\. .*")).count(), picture);
        assertFalse(picture.contains("selectedDateTime"), picture);
        // Oldest onset first: the PR stand's check at 10:17, the upload errors, the schedule, the task failure.
        var heads = picture.lines().filter(l -> l.matches("\\d\\. .*")).toList();
        assertTrue(heads.get(0).startsWith("1. since 10:17:13.495, new (not seen in the 7 days before) — Check [ errorCode=21"), picture);
        assertEquals("2. since 10:24:50.259, new (not seen in the 7 days before) — SpectrumException: Загрузка файлов невозможна", heads.get(1));
        assertTrue(heads.get(2).startsWith("3. since 10:48:24.735, new (not seen in the 7 days before) — Schedules$UnrecognizableSchedule: "), picture);
        assertEquals("4. since 10:56:52.561, more than usual (6 now, usually 0) — SpectrumException: Не найдена доступная задача с классом "
                + "ru.it_spectrum.asv.ssj.bc.tasks.DeleteDraftUploadsDelegate [configuration: task worker]", heads.get(3));
        // One failure: the ssj line, then the scheduler's two lines of the same task, joined by the key.
        assertTrue(picture.contains("\n   where: ssj-main 10:56:52.561, then scheduler-main 10:56:52.751; 12 lines in 3 groups, "
                + "one failure across services by taskExecutionId=500004 (followKey shows its lines in order)\n"
                + "   all 6 lines: pod ssj-main-asv-…p-connect-76566db777-gbwck (7% in other lines of ssj-main), node_name k8s-node3.example.internal (15%) (+4 more)\n"
                + "   restarts: none of ssj-main, scheduler-main restarted in the window"), picture);
        assertTrue(picture.contains("\n   all 5 lines: userId 1002 (rare in other lines of ssj-main), process.thread.name Информация по загрузке (0%) (+1 more)\n"), picture);
        assertTrue(picture.endsWith("\nNot in the picture: 5 groups seen before at the usual rate (7 lines), noise 38 lines."), picture);
        // The sample holds every line of the window: no count request for the onsets.
        assertFalse(requests.stream().anyMatch(r -> r.contains(" [300s]")), requests.toString());
    }

    @Test
    void aCutSampleTakesTheOnsetFromCountsInStepsAndFromTheSampleWhenTheyFail() {
        var requests = new ArrayList<String>();
        var client = stand.stand(requests);
        var text = stand.service(client, 64 * 1024, rules).summarize("dev", QUERY, "now-4h", "now", 20);
        assertTrue(text.contains(": newest 20 lines sampled (more exist), spanning 11:55:58.343–12:34:57.427, "), text);
        // The first upload error of the window is at 10:24:50, before the sample: its 5-minute step began at 10:20.
        assertTrue(picture(text).startsWith("Incident picture: new or growing errors, oldest first (the groups below are the evidence):\n"
                + "1. since about 10:20, new (not seen in the 7 days before) — SpectrumException: Загрузка файлов невозможна\n"
                + "   where: ssj-main; 3 sampled lines\n"), text);
        var timeline = requests.stream().filter(r -> r.contains(" [300s]")).toList();
        assertEquals(1, timeline.size(), requests.toString());
        assertTrue(timeline.getFirst().startsWith("sum by (mcp_fragment, instance) (count_over_time({namespace=\"dev\", app=~\"asv-app|sp-app\", "
                + "instance=\"ssj-main\"} |~ \"ERROR|Exception|Caused by\" |~ \""), timeline.getFirst());
        // Without the counts the onset is the first sampled line, and the text says the older lines were not read.
        doThrow(Errors.failure(ErrorCode.UPSTREAM_RATE_LIMITED, "limited"))
                .when(client).queryRange(anyString(), contains(" [300s]"), any(), any(), anyInt(), any(), notNull(), anyString());
        text = stand.service(client, 64 * 1024, rules).summarize("dev", QUERY, "now-4h", "now", 20);
        assertTrue(picture(text).contains("\n1. first in the sample at 12:22:30.863 (older lines of the window were not read), new "), picture(text));
    }

    private List<IncidentPicture.Incident> incidents(List<LogEvent> events, QueryTime.Range window) {
        var groups = LogSummary.group(events, normalizer, List.of("instance"), List.of("com.example"), rules);
        var incidents = IncidentPicture.select(groups);
        for (var incident : incidents)
            IncidentPicture.fromLines(incident, window, IncidentPicture.Source.LINES, e -> normalizer.view(e, List.of("instance")).service(),
                    e -> e.labels().get("instance"));
        return incidents;
    }

    @Test
    void servicesFailingOnOneAddressAreOneIncidentOfThatDependency() {
        String refused = "java.net.ConnectException: Connection refused\n\tat java.base/sun.nio.ch.Net.connect(Net.java:579)\n"
                + "\tat com.example.Store.save(Store.java:42)";
        var window = new QueryTime.Range(Instant.parse("2026-09-24T07:00:00Z"), Instant.parse("2026-09-24T09:00:00Z"));
        var events = List.of(
                line("2026-09-24T08:10:00Z", "orders", "Failed to save order to db.example.internal:5432", refused),
                line("2026-09-24T08:10:05Z", "orders", "Failed to save order to db.example.internal:5432", refused),
                line("2026-09-24T08:11:30Z", "billing", "Cannot book payment, database db.example.internal:5432 unreachable",
                        refused.replace("Store.save(Store.java:42)", "Billing.book(Billing.java:9)")),
                line("2026-09-24T08:30:00Z", "billing", "Invoice template missing", null));
        var incidents = incidents(events, window);
        // The two services share the rule and the address; the unrelated message is an incident of its own.
        assertEquals(2, incidents.size());
        var shared = incidents.getFirst();
        assertEquals(2, shared.groups.size());
        assertEquals(Instant.parse("2026-09-24T08:10:00Z"), shared.onset);
        assertEquals(List.of("orders", "billing"), List.copyOf(shared.services.keySet()));
        var text = String.join("\n", IncidentPicture.render(incidents, 5, LogSummary.group(events, normalizer, List.of("instance"), List.of(), rules),
                0, window, null, ZONE));
        assertTrue(text.contains("\n1. since 11:10:00.000, history not checked — ConnectException: Connection refused [dependency: network connection] "
                + "endpoint db.example.internal:5432\n   where: orders 11:10:00.000, then billing 11:11:30.000; 3 lines in 2 groups\n"), text);
        assertTrue(text.startsWith("Incident picture: the biggest errors, oldest first (their history was not checked"), text);
        var overMidnight = new QueryTime.Range(Instant.parse("2026-09-23T20:00:00Z"), window.end());
        text = String.join("\n", IncidentPicture.render(incidents, 5, List.of(), 0, overMidnight, null, ZONE));
        assertTrue(text.contains("\n1. since 09-24 11:10:00.000, history not checked — ") && text.contains(" orders 09-24 11:10:00.000, then billing 09-24 11:11:30.000;"), text);
    }

    @Test
    void addressesComeFromUrlsAndHostPortsButNeverFromFramesOrUserInfo() {
        var events = List.of(
                line("2026-09-24T08:00:00Z", "a", "Call to https://reader:secret@api.example.org:8443/v1/items failed", null),
                line("2026-09-24T08:00:00Z", "b", "Timeout at (Client.java:120) reading 192.0.2.7:6379", null),
                line("2026-09-24T08:00:00Z", "c", "Failed in Client.java:120 after 3 retries", null));
        var groups = LogSummary.group(events, normalizer, List.of("instance"), List.of());
        var endpoints = new HashMap<String, String>();
        for (var group : groups) endpoints.put(group.lastView.service(), IncidentPicture.endpoint(group));
        assertEquals("api.example.org:8443", endpoints.get("a"));
        assertEquals("192.0.2.7:6379", endpoints.get("b"));
        assertNull(endpoints.get("c"));
    }

    @Test
    void theOnsetIsWhereTheLinesStopFittingTheUsualRate() {
        var window = new QueryTime.Range(Instant.parse("2026-09-24T00:00:00Z"), Instant.parse("2026-09-24T04:00:00Z"));
        var times = new ArrayList<Instant>();
        // Six lines spread over the window, as usual, then a burst of 30 in its last 20 minutes.
        for (int i = 0; i < 6; i++) times.add(window.start().plus(Duration.ofMinutes(30L * i + 5)));
        for (int i = 0; i < 30; i++) times.add(Instant.parse("2026-09-24T03:40:00Z").plusSeconds(30L * i));
        long[] ones = new long[times.size()];
        Arrays.fill(ones, 1);
        assertEquals(Instant.parse("2026-09-24T03:40:00Z"), IncidentPicture.changePoint(times, ones, 6, window));
        // A new group begins at its first line.
        assertEquals(times.getFirst(), IncidentPicture.changePoint(times, ones, 0, window));
        assertEquals(0, IncidentPicture.surprise(3, 5));
        assertTrue(IncidentPicture.surprise(30, 0.5) > IncidentPicture.surprise(10, 0.5));
    }

    @Test
    void restartsSayWhatHappenedAroundTheOnset() {
        var window = new QueryTime.Range(Instant.parse("2026-09-24T07:00:00Z"), Instant.parse("2026-09-24T09:00:00Z"));
        var events = new ArrayList<LogEvent>();
        for (int i = 0; i < 4; i++)
            events.add(line("2026-09-24T08:0" + (i + 1) + ":00Z", "orders", "Order queue is stuck " + i, null));
        events.add(line("2026-09-24T08:02:00Z", "billing", "Payment ledger is locked", null));
        var incidents = incidents(events, window);
        var orders = incidents.stream().filter(i -> i.services.containsKey("orders")).findFirst().orElseThrow();
        var billing = incidents.stream().filter(i -> i.services.containsKey("billing")).findFirst().orElseThrow();
        // orders: a deploy five minutes before its first error, then a restart after the last one; billing: nothing.
        var marks = List.of(
                start("2026-09-24T07:54:00Z", "orders-1", "Starting OrderService v1.4.0 using Java 21"),
                start("2026-09-24T07:55:30Z", "orders-1", "Started OrderService in 90.1 seconds (process running for 91.0)"),
                start("2026-09-24T07:30:00Z", "orders-0", "Starting OrderService v1.3.9 using Java 21"),
                start("2026-09-24T07:31:00Z", "orders-0", "Started OrderService in 60.0 seconds (process running for 61.0)"),
                start("2026-09-24T08:30:00Z", "orders-2", "Starting OrderService v1.4.0 using Java 21"),
                start("2026-09-24T08:31:00Z", "orders-2", "Started OrderService in 60.0 seconds (process running for 61.0)"));
        var sorted = new ArrayList<>(marks);
        sorted.sort(Comparator.comparingLong(LogEvent::nanos));
        var starts = ServiceStarts.of("{instance=\"orders\"}", sorted, false, normalizer, List.of("instance"), window.end());
        assertEquals("orders restarted at 10:55:30.000, just before (deploy: v1.3.9 → v1.4.0); "
                + "orders restarted at 11:31:00.000 after it began, none of these lines after it", IncidentPicture.restarts(orders, starts, ZONE, false));
        assertEquals("none of billing restarted in the window", IncidentPicture.restarts(billing, starts, ZONE, false));
        assertEquals("not checked (the query for start lines failed)",
                IncidentPicture.restarts(billing, ServiceStarts.failed("{a=\"b\"}", ErrorCode.UPSTREAM_TIMEOUT), ZONE, false));
        assertNull(IncidentPicture.restarts(billing, null, ZONE, false));
    }

    @Test
    void nothingNewOrGrowingIsOneLine() {
        var group = LogSummary.group(List.of(line("2026-09-24T08:00:00Z", "a", "Cache refresh took too long", null)), normalizer,
                List.of("instance"), List.of()).getFirst();
        group.verdict = GroupHistory.classify(new long[]{1, 1, 1, 1, 1, 1, 1}, null, Duration.ofHours(1));
        assertTrue(IncidentPicture.select(List.of(group)).isEmpty());
        assertEquals(List.of("Incident picture: nothing new or growing, every group below was seen before at its usual rate. "
                        + "If something is broken now, its lines may be outside this query: widen the selector or the filter."),
                IncidentPicture.render(List.of(), 5, List.of(group), 0, null, null, ZONE));
    }
}
