package ru.it_spectrum.ai.loki.mcp.service;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.client.LokiResponses.*;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionAuth;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionDefinition;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionLimits;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;
import ru.it_spectrum.ai.loki.mcp.model.LogEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * History of summary groups against what DEV Loki counted for the window of asva2-dev-contrast-window.jsonl.
 */
class GroupHistoryTest {
    private static final List<String> SERVICE_LABELS = List.of("applicationName", "instance", "container");
    private static final List<String> PACKAGES = List.of("ru.it_spectrum.asv", "ru.it_spectrum.core");
    private static final String QUERY = "{namespace=\"dev\", app=~\"asv-app|sp-app\"} |~ \"ERROR|Exception|Caused by\"";
    private static final Pattern STAGES = Pattern.compile(" \\|~ \"((?:[^\"\\\\]|\\\\.)*)\" \\| regexp \"\\(\\?P<mcp_fragment>(.*)\\)\"$");
    private static final Pattern BACKGROUND = Pattern.compile("\\{namespace=\"dev\", app=~\"asv-app\\|sp-app\", instance=~\"([^\"]+)\"}");
    private static final Pattern RANGE = Pattern.compile(" \\[(\\d+)s](?: offset (\\d+)s)?\\)\\)$");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final EventNormalizer normalizer = new EventNormalizer();
    private final JsonNode history = history();
    private final Instant end = Instant.ofEpochSecond(0, Long.parseLong(history.get("windowEnd").asString()));
    private final Instant start = Instant.ofEpochSecond(0, Long.parseLong(history.get("windowStart").asString()));

    private static JsonNode history() {
        try (var input = GroupHistoryTest.class.getResourceAsStream("/fixtures/asva2-dev-contrast-history.json")) {
            return MAPPER.readTree(new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<LogSummary.Group> groups(List<LogEvent> events) {
        return LogSummary.group(events, normalizer, SERVICE_LABELS, PACKAGES);
    }

    /**
     * The regular expression Loki gets, read back from the LogQL string of the {@code | regexp} stage.
     */
    private static Pattern fragments(GroupHistory.Batch batch) {
        var stages = STAGES.matcher(batch.scope());
        assertTrue(stages.find(), batch.scope());
        assertEquals(stages.group(1), stages.group(2));
        return Pattern.compile(stages.group(2).replace("\\\\", "\u0000").replace("\\\"", "\"").replace("\u0000", "\\"));
    }

    @Test
    void oneRequestCountsEveryGroupByTheFragmentItsLinesHold() {
        var events = Fixtures.events("asva2-dev-contrast-window.jsonl");
        var groups = groups(events);
        assertEquals(13, groups.size());
        // The fragments are the ones counted on the stand, one per group.
        var measured = new HashSet<String>();
        for (var group : history.get("groups")) measured.add(group.get("fragment").asString());
        for (var group : groups) assertTrue(measured.contains(GroupHistory.fragment(group)), group.template);
        var batches = GroupHistory.batches(QUERY, groups);
        assertEquals(1, batches.size());
        var batch = batches.getFirst();
        assertEquals(13, batch.fragments().size());
        assertEquals(List.of("mcp_fragment", "instance"), batch.by());
        assertTrue(batch.scope().startsWith("{namespace=\"dev\", app=~\"asv-app|sp-app\", instance=~\"nsi-main|sbp-main|scheduler-main|sec-main|"
                + "sms-main|ssj-ek-export-main|ssj-main|ssj-pr-1396\"} |~ \"ERROR|Exception|Caused by\" |~ \""), batch.scope());
        assertEquals("{namespace=\"dev\", app=~\"asv-app|sp-app\", instance=~\"nsi-main|sbp-main|scheduler-main|sec-main|sms-main|ssj-ek-export-main|"
                + "ssj-main|ssj-pr-1396\"} |~ \"ERROR|Exception|Caused by\" |~ \"<log text>\" | regexp \"(?P<mcp_fragment><log text>)\"", batch.logged());
        // Every line of the window holds its group's fragment as it is written in the raw line, and Loki's leftmost match
        // takes that fragment and not another group's.
        var pattern = fragments(batch);
        for (var event : events) {
            var own = groups.stream().filter(g -> g.template.equals(groups(List.of(event)).getFirst().template)).findFirst().orElseThrow();
            Matcher matcher = pattern.matcher(event.line());
            assertTrue(matcher.find(), event.line());
            assertEquals(batch.fragments().get(own), matcher.group(), event.line());
        }
    }

    @Test
    void fragmentOfAJsonLineMatchesItsRawTextAndAServiceFromJsonAddsNoMatcher() {
        var event = new LogEvent("1", Map.of("namespace", "dev"),
                "{\"service\":{\"name\":\"sbp-backend\"},\"log\":{\"level\":\"ERROR\"},\"message\":\"Справочник \\\"ФОИВ\\\" (код) не найден для кода 17\"}", Map.of());
        var group = groups(List.of(event)).getFirst();
        assertNull(group.serviceLabel);
        assertEquals("Справочник \\\"ФОИВ\\\" (код) не найден для кода", GroupHistory.fragment(group));
        var batch = GroupHistory.batches("{namespace=\"dev\"} |= \"x\"", List.of(group)).getFirst();
        assertTrue(batch.scope().startsWith("{namespace=\"dev\"} |= \"x\" |~ \""), batch.scope());
        assertEquals(List.of("mcp_fragment"), batch.by());
        var matcher = fragments(batch).matcher(event.line());
        assertTrue(matcher.find(), batch.scope());
        assertEquals(GroupHistory.fragment(group), matcher.group());
        // Groups of several services of one label: a regular expression with the values quoted.
        var two = groups(List.of(new LogEvent("1", Map.of("instance", "a.b"), "Failed to load cache entry 17", Map.of()),
                new LogEvent("2", Map.of("instance", "c"), "Failed to load cache entry 18", Map.of())));
        assertTrue(GroupHistory.batches("{app=\"x\"}", two).getFirst().scope().startsWith("{app=\"x\", instance=~\"a\\\\.b|c\"} |~ \"Failed to load cache entry\""));
        // Frame lines and messages without a long literal part are not counted; no selector, no batches.
        assertNull(GroupHistory.fragment(groups(List.of(new LogEvent("1", Map.of(), "\tat a.B.c(B.java:1)", Map.of()))).getFirst()));
        assertNull(GroupHistory.fragment(groups(List.of(new LogEvent("1", Map.of(), "id 17 of 18", Map.of()))).getFirst()));
        assertTrue(GroupHistory.batches("count_over_time({a=\"b\"}[1m])", two).isEmpty());
    }

    @Test
    void manyLongFragmentsGoIntoSeveralRequestsUnderTheUrlLimit() {
        var events = new ArrayList<LogEvent>();
        for (int i = 0; i < 40; i++)
            events.add(new LogEvent(Integer.toString(i), Map.of("instance", "service-" + (i % 5)),
                    "Не удалось обработать документ категории " + (char) ('А' + i) + "-вариант и отправить его во внешнюю систему", Map.of()));
        var groups = groups(events);
        assertEquals(40, groups.size());
        var batches = GroupHistory.batches(QUERY, groups);
        assertTrue(batches.size() > 1, batches.size() + " batches");
        assertEquals(40, batches.stream().mapToInt(b -> b.fragments().size()).sum());
        var window = new QueryTime.Range(start, end);
        for (var batch : batches)
            for (var request : List.of(GroupHistory.previousDays(batch, window), GroupHistory.sameHours(batch, window)))
                assertTrue(URLEncoder.encode(request.expression(), StandardCharsets.UTF_8).length() <= GroupHistory.QUERY_BYTES, request.expression());
    }

    @Test
    void requestsEndAtTheWindowStartAndAtTheSameHoursThroughAnOffset() {
        var window = new QueryTime.Range(start, end);
        var batch = GroupHistory.batches("{a=\"b\"}", groups(List.of(new LogEvent("1", Map.of(), "Connection refused by peer 17", Map.of())))).getFirst();
        // 2026-09-24 06:06:56Z–10:06:56Z; the next UTC midnight is 2026-09-25T00:00:00Z.
        var previous = GroupHistory.previousDays(batch, window);
        assertEquals("sum by (mcp_fragment) (count_over_time({a=\"b\"} |~ \"Connection refused by peer\" | regexp "
                + "\"(?P<mcp_fragment>Connection refused by peer)\" [86400s] offset 64384s))", previous.expression());
        assertEquals("sum by (mcp_fragment) (count_over_time({a=\"b\"} |~ \"<log text>\" | regexp \"(?P<mcp_fragment><log text>)\" [86400s] offset 64384s))",
                previous.logged());
        assertEquals(Instant.parse("2026-09-19T00:00:00Z"), previous.start());
        assertEquals(Instant.parse("2026-09-25T00:00:00Z"), previous.end());
        var same = GroupHistory.sameHours(batch, window);
        assertTrue(same.expression().endsWith(" [14400s] offset 49984s))"), same.expression());
        assertEquals(Instant.parse("2026-09-18T00:00:00Z"), same.start());
        assertEquals(Instant.parse("2026-09-25T00:00:00Z"), same.end());
        // A window over 6 hours is counted in itself only (a second, unused point a day before); "now-24h" with
        // nanoseconds is still 24 hours.
        var day = GroupHistory.sameHours(batch, new QueryTime.Range(end.minusSeconds(86_400).plusNanos(123), end.plusNanos(123)));
        assertTrue(day.expression().endsWith(" [86400s] offset 49984s))"), day.expression());
        assertEquals(Instant.parse("2026-09-24T00:00:00Z"), day.start());
        // A window starting at midnight needs no offset.
        assertTrue(GroupHistory.previousDays(batch, new QueryTime.Range(Instant.parse("2026-09-24T00:00:00Z"), end)).expression().endsWith(" [86400s]))"));
    }

    @Test
    void verdictsNameNewGrowthAndTheUsualCount() {
        var hours = java.time.Duration.ofHours(4);
        assertEquals("new: not seen in the 7 days before", GroupHistory.classify(new long[7], null, hours).text());
        assertEquals("more than usual: 35 in this window, usually 6 at these hours; 1334 in the 7 days before",
                GroupHistory.classify(new long[]{126, 872, 107, 0, 3, 140, 86}, new long[]{35, 5, 7, 7, 0, 0, 12, 6}, hours).text());
        // Three lines where there are usually none are not enough to call it growth.
        assertEquals("seen before: 22 in the 7 days before, usually 0 at these hours",
                GroupHistory.classify(new long[]{3, 3, 3, 3, 4, 3, 3}, new long[]{3, 0, 0, 0, 0, 0, 0, 0}, hours).text());
        assertEquals("seen before: 2 in the 7 days before", GroupHistory.classify(new long[]{0, 0, 0, 0, 0, 0, 2}, null, hours).text());
        // A long window against the daily median scaled to its length.
        assertEquals("more than usual: 59 in this window, usually about 10 in 12h; 140 in the 7 days before",
                GroupHistory.classify(new long[]{20, 20, 20, 20, 20, 20, 20}, new long[]{59, 0}, java.time.Duration.ofHours(12)).text());
        assertEquals("seen before: 140 in the 7 days before, usually about 20 in 24h",
                GroupHistory.classify(new long[]{20, 20, 20, 20, 20, 20, 20}, new long[]{25, 0}, java.time.Duration.ofHours(24)).text());
        assertEquals(0.0144, GroupHistory.poissonTail(3, 0.5), 0.0001);
        assertEquals(1.0, GroupHistory.poissonTail(0, 2), 1e-9);
    }

    /**
     * A Loki that answers the log query from the window and day-before fixtures, the start/stop query with nothing,
     * and a count request with one series per fixture group whose fragment it holds, from asva2-dev-contrast-history.json.
     */
    LokiHttpClient stand(List<String> requests) {
        var client = mock(LokiHttpClient.class);
        var events = new ArrayList<>(Fixtures.events("asva2-dev-contrast-window.jsonl"));
        events.addAll(Fixtures.events("asva2-dev-contrast-yesterday.jsonl"));
        var background = Fixtures.events("asva2-dev-contrast-background.jsonl");
        org.mockito.stubbing.Answer<QueryResponse> answer = invocation -> {
            String query = invocation.getArgument(1);
            Instant from = invocation.getArgument(2), to = invocation.getArgument(3);
            int limit = invocation.getArgument(4);
            BigDecimal step = invocation.getArgument(6);
            if (step != null) {
                // The server's own log names the request without the text of the stand's lines.
                String logged = invocation.getArguments().length > 7 ? invocation.getArgument(7) : query;
                assertTrue(logged.contains("|~ \"<log text>\"") && !logged.contains("Загрузка"), logged);
                synchronized (requests) {
                    requests.add(query);
                }
                return new QueryResponse(new Matrix(series(query, from, to, events)), new QueryStats(0L), List.of());
            }
            var page = new ArrayList<LogStream>();
            var slice = BACKGROUND.matcher(query);
            List<LogEvent> source = query.equals(QUERY) ? events : List.of();
            if (slice.matches()) {
                // The background of the fields: the stream selector of the query narrowed to the services, no pipeline.
                synchronized (requests) {
                    requests.add(query);
                }
                var instances = Set.of(slice.group(1).split("\\|"));
                source = background.stream().filter(e -> instances.contains(e.labels().get("instance"))).toList();
            }
            var inside = source.stream().filter(e -> !QueryTime.fromNanos(e.timestampNanos()).isBefore(from)
                    && QueryTime.fromNanos(e.timestampNanos()).isBefore(to)).sorted(Comparator.comparingLong(LogEvent::nanos).reversed()).limit(limit).toList();
            for (var e : inside) page.add(new LogStream(e.labels(), List.of(new LogEntry(e.timestampNanos(), e.line(), Map.of()))));
            return new QueryResponse(new Streams(page), new QueryStats(0L), List.of());
        };
        doAnswer(answer).when(client).queryRange(anyString(), anyString(), any(), any(), anyInt(), any(), any());
        doAnswer(answer).when(client).queryRange(anyString(), anyString(), any(), any(), anyInt(), any(), any(), anyString());
        return client;
    }

    private List<MetricSeries> series(String expression, Instant from, Instant to, List<LogEvent> events) {
        var range = RANGE.matcher(expression);
        assertTrue(range.find(), expression);
        long length = Long.parseLong(range.group(1));
        long offset = range.group(2) == null ? 0 : Long.parseLong(range.group(2));
        if (length != GroupHistory.DAY_SECONDS && length != end.getEpochSecond() - start.getEpochSecond()) return timeline(expression, from, to, length, events);
        var result = new ArrayList<MetricSeries>();
        for (var group : history.get("groups")) {
            String fragment = group.get("fragment").asString();
            if (!expression.contains(GroupHistory.logqlEscape(GroupHistory.regexEscape(fragment)))) continue;
            var samples = new ArrayList<MetricSample>();
            if (length == GroupHistory.DAY_SECONDS) {
                // Evaluations must end their 24 hours exactly at the window start.
                assertEquals(start.getEpochSecond(), to.getEpochSecond() - offset);
                for (int k = 0; k < GroupHistory.DAYS; k++)
                    samples.add(new MetricSample(BigDecimal.valueOf(to.getEpochSecond() - k * GroupHistory.DAY_SECONDS),
                            group.get("previousDays").get(GroupHistory.DAYS - 1 - k).asString()));
            } else {
                assertEquals(end.getEpochSecond() - start.getEpochSecond(), length);
                assertEquals(end.getEpochSecond(), to.getEpochSecond() - offset);
                samples.add(new MetricSample(BigDecimal.valueOf(to.getEpochSecond()), group.get("window").asString()));
                for (int k = 1; k <= GroupHistory.DAYS; k++)
                    samples.add(new MetricSample(BigDecimal.valueOf(to.getEpochSecond() - k * GroupHistory.DAY_SECONDS),
                            group.get("sameHours").get(GroupHistory.DAYS - k).asString()));
            }
            assertEquals(from.getEpochSecond(), to.getEpochSecond() - (samples.size() - 1) * GroupHistory.DAY_SECONDS);
            // A group of several services: its count is under the first one, which the group still sums.
            result.add(new MetricSeries(Map.of("mcp_fragment", fragment, "instance", group.get("services").get(0).asString()), samples));
        }
        return result;
    }

    /**
     * The onset counts of the incidents: the lines of the fixtures holding a fragment of the request, leftmost first as
     * Loki's regexp takes it, counted in steps of {@code length} ending at multiples of it, by fragment and instance.
     */
    private List<MetricSeries> timeline(String expression, Instant from, Instant to, long length, List<LogEvent> events) {
        assertEquals(0, from.getEpochSecond() % length);
        assertEquals(0, to.getEpochSecond() % length);
        var fragments = new ArrayList<String>();
        for (var group : history.get("groups")) {
            String fragment = group.get("fragment").asString();
            if (expression.contains(GroupHistory.logqlEscape(GroupHistory.regexEscape(fragment)))) fragments.add(fragment);
        }
        var counts = new TreeMap<String, TreeMap<Long, Long>>();
        for (var event : events) {
            String fragment = null;
            int at = Integer.MAX_VALUE;
            for (String candidate : fragments) {
                int index = event.line().indexOf(candidate);
                if (index >= 0 && (index < at || (index == at && candidate.length() > fragment.length()))) {
                    at = index;
                    fragment = candidate;
                }
            }
            if (fragment == null) continue;
            long second = QueryTime.fromNanos(event.timestampNanos()).getEpochSecond();
            long evaluation = Math.ceilDiv(second + 1, length) * length;
            if (evaluation < from.getEpochSecond() || evaluation > to.getEpochSecond()) continue;
            counts.computeIfAbsent(fragment + "\u0000" + event.labels().get("instance"), k -> new TreeMap<>()).merge(evaluation, 1L, Long::sum);
        }
        var result = new ArrayList<MetricSeries>();
        for (var series : counts.entrySet()) {
            String[] key = series.getKey().split("\u0000");
            var samples = new ArrayList<MetricSample>();
            series.getValue().forEach((evaluation, count) -> samples.add(new MetricSample(BigDecimal.valueOf(evaluation), Long.toString(count))));
            result.add(new MetricSeries(Map.of("mcp_fragment", key[0], "instance", key[1]), samples));
        }
        return result;
    }

    private QueryService service(LokiHttpClient client, int maxResponseBytes) {
        return service(client, maxResponseBytes, List.of());
    }

    QueryService service(LokiHttpClient client, int maxResponseBytes, List<ru.it_spectrum.ai.loki.mcp.connection.LogRule> rules) {
        var registry = new ConnectionRegistry(List.of(new ConnectionDefinition("dev", null, null, URI.create("http://localhost:1"),
                ConnectionAuth.NONE, null, ZoneId.of("Europe/Moscow"),
                new ConnectionLimits(100, 100, 8 * 1024 * 1024, maxResponseBytes, 1000, 86400, 100, 1000), SERVICE_LABELS, PACKAGES, rules)));
        return new QueryService(registry, client, Clock.fixed(end, ZoneOffset.UTC));
    }

    @Test
    void summaryOfTheWindowSaysWhatIsNewWhatGrewAndWhatIsGone() {
        var requests = new ArrayList<String>();
        var text = service(stand(requests), 64 * 1024).summarize("dev", QUERY, "now-4h", "now", null);
        assertTrue(text.contains(": all 67 lines, spanning ") && text.contains(", 13 distinct messages.\n"
                + "Compared with the 7 days before (lines of this query with the same text): 4 groups new, 2 more than usual, 7 seen before.\n"), text);
        // All 13 groups in one request for the 7 days before and one for the same hours; the background of the fields
        // in 12 slices over the services of the groups of 3 lines or more.
        assertEquals(14, requests.size(), requests.toString());
        assertEquals(12, requests.stream().filter(r -> r.equals("{namespace=\"dev\", app=~\"asv-app|sp-app\", "
                + "instance=~\"nsi-main|scheduler-main|sec-main|ssj-main|ssj-pr-1396\"}")).count(), requests.toString());
        assertTrue(text.contains("\n         more than usual: 6 in this window, usually 0 at these hours; 14 in the 7 days before\n"
                + "         all 6 lines: pod ssj-main-asv-…p-connect-76566db777-gbwck (7% in other lines of ssj-main), "), text);
        assertTrue(text.contains("\n         all 5 lines: userId 1002 (rare in other lines of ssj-main), "), text);
        assertTrue(text.contains("\n         more than usual: 35 in this window, usually 6 at these hours; 1334 in the 7 days before\n"), text);
        assertTrue(text.contains("\n         more than usual: 6 in this window, usually 0 at these hours; 14 in the 7 days before\n"), text);
        assertTrue(text.contains(": Загрузка файлов невозможна\n         at ru.it_spectrum.asv.bc.loader.action.CreateUploadAction$LoaderServiceAsyncAction.run("
                + "CreateUploadAction.java:109)\n         new: not seen in the 7 days before\n"), text);
        assertEquals(4, count(text, "\n         new: not seen in the 7 days before\n"), text);
        assertEquals(7, count(text, "\n         seen before: "), text);
        assertTrue(text.contains("\n         seen before: 22 in the 7 days before, usually 0 at these hours\n"), text);
        assertTrue(text.contains("\n         seen before: 19 in the 7 days before, usually 0 at these hours\n"), text); // nsi-main and sec-main
        // The day before had a burst of connect timeouts: a group of that sample the window no longer has.
        assertTrue(text.contains("\nSeen at these hours a day earlier, not now (in a sample of 25 lines of 2026-09-23 09:06:56–13:06:56 (+03:00)):\n"), text);
        assertTrue(text.contains("  scheduler-main  SocketTimeoutException: Connect timed out\n") && text.contains("  ssj-ek-export-main  InterruptedException\n"), text);
        assertEquals(5, text.lines().dropWhile(l -> !l.startsWith("Seen at these hours")).skip(1).takeWhile(l -> l.startsWith("  ")).count(), text);
    }

    @Test
    void goneGroupsNeedTheWholeWindowAndEverythingFitsATightBudget() {
        var cut = service(stand(new ArrayList<>()), 64 * 1024).summarize("dev", QUERY, "now-4h", "now", 20);
        assertTrue(cut.contains(": newest 20 lines sampled (more exist), "), cut);
        assertFalse(cut.contains("Seen at these hours a day earlier"), cut);
        assertTrue(cut.contains("\nCompared with the 7 days before "), cut);
        var tight = service(stand(new ArrayList<>()), 4096).summarize("dev", QUERY, "now-4h", "now", null);
        assertTrue(LogText.bytes(tight) <= 4096 - LogText.ENVELOPE_BYTES, tight);
        assertTrue(tight.contains("Output limit reached: showing "), tight);
        assertFalse(tight.contains("Seen at these hours a day earlier"), tight);
        assertTrue(tight.contains("\n         more than usual: 35 in this window, usually 6 at these hours; 1334 in the 7 days before"), tight);
    }

    @Test
    void aFailedCountSaysSoWithoutFailingTheSummary() {
        // Without the same hours the groups seen before cannot be "more than usual".
        var client = stand(new ArrayList<>());
        doThrow(Errors.failure(ErrorCode.UPSTREAM_RATE_LIMITED, "limited"))
                .when(client).queryRange(anyString(), contains(" [14400s] "), any(), any(), anyInt(), any(), notNull(), anyString());
        var text = service(client, 64 * 1024).summarize("dev", QUERY, "now-4h", "now", null);
        assertTrue(text.contains("\nCompared with the 7 days before (lines of this query with the same text): 4 groups new, 0 more than usual, 9 seen before.\n"), text);
        assertTrue(text.contains("\n         seen before: 1334 in the 7 days before\n"), text);
        // Without the 7 days before nothing is known.
        doThrow(Errors.failure(ErrorCode.UPSTREAM_TIMEOUT, "timeout"))
                .when(client).queryRange(anyString(), contains(" [86400s] "), any(), any(), anyInt(), any(), notNull(), anyString());
        text = service(client, 64 * 1024).summarize("dev", QUERY, "now-4h", "now", null);
        assertTrue(text.contains("\nHistory of the groups was not checked (Loki did not answer in time or refused the count).\n"), text);
        assertEquals(13, count(text, "\n         history not checked\n"), text);
        assertTrue(text.contains("\nSeen at these hours a day earlier, not now ("), text);
    }

    @Test
    void aLongWindowReadWholeTakesItsCountFromTheSample() {
        var client = mock(LokiHttpClient.class);
        var requests = new ArrayList<String>();
        var lines = new ArrayList<LogEntry>();
        for (int i = 0; i < 45; i++) lines.add(new LogEntry(QueryTime.nanos(end.minusSeconds(600L * i + 1)), "Connection refused by peer " + i, Map.of()));
        doAnswer(invocation -> new QueryResponse(new Streams(invocation.getArgument(1).equals("{app=\"x\"}") && invocation.<Instant>getArgument(3).equals(end)
                ? List.of(new LogStream(Map.of("app", "x"), lines)) : List.of()), new QueryStats(0L), List.of()))
                .when(client).queryRange(anyString(), anyString(), any(), any(), anyInt(), any(), any());
        doAnswer(invocation -> {
            Instant to = invocation.getArgument(3);
            requests.add(invocation.getArgument(1));
            var samples = new ArrayList<MetricSample>();
            for (int k = 0; k < GroupHistory.DAYS; k++)
                samples.add(new MetricSample(BigDecimal.valueOf(to.getEpochSecond() - k * GroupHistory.DAY_SECONDS), "20"));
            return new QueryResponse(new Matrix(List.of(new MetricSeries(Map.of("mcp_fragment", "Connection refused by peer"), samples))),
                    new QueryStats(0L), List.of());
        }).when(client).queryRange(anyString(), anyString(), any(), any(), anyInt(), any(), any(), anyString());
        var text = service(client, 64 * 1024).summarize("dev", "{app=\"x\"}", "now-12h", "now", null);
        assertTrue(text.contains("\n         more than usual: 45 in this window, usually about 10 in 12h; 140 in the 7 days before\n"), text);
        assertEquals(1, requests.size(), requests.toString());
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) count++;
        return count;
    }
}
