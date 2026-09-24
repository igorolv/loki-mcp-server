package ru.it_spectrum.ai.loki.mcp;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionAuth;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionDefinition;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionLimits;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.service.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LokiCompatibilityTest {
    @ParameterizedTest
    @ValueSource(strings = {"2.6.1", "3.6.0"})
    @Timeout(240)
    void logsCountsMetricsAndDiscoveryAgainstIsolatedLoki(String version) throws Exception {
        String config = """
                auth_enabled: false
                server:
                  http_listen_port: 3100
                common:
                  path_prefix: /tmp/loki
                  replication_factor: 1
                  ring:
                    kvstore:
                      store: inmemory
                schema_config:
                  configs:
                    - from: 2020-01-01
                      store: boltdb-shipper
                      object_store: filesystem
                      schema: v11
                      index:
                        prefix: index_
                        period: 24h
                storage_config:
                  boltdb_shipper:
                    active_index_directory: /tmp/loki/index
                    cache_location: /tmp/loki/cache
                  filesystem:
                    directory: /tmp/loki/chunks
                limits_config:
                  reject_old_samples: false
                """;
        if (version.startsWith("3")) config += "  allow_structured_metadata: false\n";
        try (var loki = new GenericContainer<>("grafana/loki:" + version)
                .withCopyToContainer(Transferable.of(config.getBytes(StandardCharsets.UTF_8)), "/etc/loki/test.yaml")
                .withCommand("-config.file=/etc/loki/test.yaml")
                .withExposedPorts(3100).waitingFor(Wait.forHttp("/ready").forStatusCode(200).withStartupTimeout(Duration.ofSeconds(120)))) {
            loki.start();
            URI url = URI.create("http://" + loki.getHost() + ":" + loki.getMappedPort(3100));
            // Loki 3.x adds service_name itself; naming the service by shard keeps both versions comparable.
            var registry = new ConnectionRegistry(List.of(new ConnectionDefinition("fixture", null, null, url,
                    ConnectionAuth.NONE, null, ZoneOffset.UTC, ConnectionLimits.DEFAULTS, List.of("shard"))));
            // Only this container-derived URL can be used for ingestion; no external config or live URLs.
            Instant base = Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            String a = QueryTime.nanos(base.plusNanos(123456789)), b = QueryTime.nanos(base.plusSeconds(1).plusNanos(987654321));
            String ecs = "{\"service\":{\"name\":\"backend\"},\"log\":{\"level\":\"ERROR\"},\"message\":\"Ошибка 🐈\",\"error\":{\"stack_trace\":\"java.io.IOException: x\\n\\tat a.B(B.java:1)\"}}";
            String body = new tools.jackson.databind.json.JsonMapper().writeValueAsString(java.util.Map.of("streams", List.of(
                    java.util.Map.of("stream", java.util.Map.of("fixture", "s04", "shard", "a", "level", "info"), "values", List.of(List.of(a, ecs), List.of(b, "second"))),
                    java.util.Map.of("stream", java.util.Map.of("fixture", "s04", "shard", "b"), "values", List.of(List.of(a, "same timestamp other stream"))))));
            try (var http = HttpClient.newHttpClient(); var client = new LokiHttpClient(registry)) {
                var pushed = http.send(HttpRequest.newBuilder(url.resolve("/loki/api/v1/push"))
                        .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(204, pushed.statusCode(), pushed.body());
                var service = new QueryService(registry, client);
                String start = base.toString(), end = base.plusSeconds(3).toString(), selector = "{fixture=\"s04\"}";
                String time = LogText.TIME.format(base.plusNanos(123456789).atZone(ZoneOffset.UTC));
                var logs = service.logs("fixture", selector, start, end, 10, null);
                assertTrue(logs.contains(", all 3 lines:\n"), logs);
                assertTrue(logs.contains(time + " INFO  a  Ошибка 🐈\n    java.io.IOException: x\n    at a.B(B.java:1)\n"), logs);
                assertTrue(logs.contains(time + " -     b  same timestamp other stream\n"), logs);
                assertTrue(logs.endsWith("Shown all 3 matching lines."), logs);
                assertEquals(2, logs.lines().filter(l -> l.startsWith(time)).count());
                var limited = service.logs("fixture", selector, start, end, 2, null);
                assertTrue(limited.contains("newest 2 of more:"), limited);
                String olderEnd = QueryTime.iso(base.plusNanos(124000000), ZoneOffset.UTC);
                assertTrue(limited.contains("Older: repeat with end=\"" + olderEnd + "\""), limited);
                var older = service.logs("fixture", selector, start, olderEnd, 10, null);
                assertTrue(older.contains(", all 2 lines:\n"), older); // the millisecond ceiling re-reads the boundary instead of skipping it
                var raw = service.logs("fixture", selector + " |= \"🐈\"", start, end, 10, true);
                assertTrue(raw.contains(time + " {fixture=\"s04\", level=\"info\", "), raw);
                assertTrue(raw.contains("shard=\"a\"}  " + ecs + "\n"), raw);
                // Error -> context: the selector without the filter shows the neighbour in the other stream and the later line.
                var context = service.context("fixture", selector, time, 5, 5);
                assertTrue(context.contains(", lines: 0 before, 2 at that time, 1 after:\n"), context);
                assertEquals(2, context.lines().filter(l -> l.startsWith(">>> " + time)).count(), context);
                assertTrue(context.contains("\n" + LogText.TIME.format(base.plusSeconds(1).plusNanos(987654321).atZone(ZoneOffset.UTC)) + " INFO  a  second\n"), context);
                assertTrue(context.contains("No earlier lines within 24h before this time. No later lines within 24h after this time."), context);
                var precise = service.context("fixture", selector, base.plusSeconds(1).plusNanos(987654321).toString(), 1, 0);
                assertTrue(precise.contains("lines: 1 before, 1 at that time, 0 after:\n" + time + " "), precise);
                assertTrue(precise.contains("\n>>> " + LogText.TIME.format(base.plusSeconds(1).plusNanos(987654321).atZone(ZoneOffset.UTC)) + " INFO  a  second\n"), precise);
                var nothing = service.context("fixture", selector, base.plusSeconds(2).toString(), 1, 1);
                assertTrue(nothing.contains("lines: 1 before, 0 at that time, 0 after:\n") && nothing.contains("\n>>> (no line at exactly this time"), nothing);
                assertTrue(assertThrows(LokiOperationException.class, () -> service.context("fixture", selector + " |= \"x\"", time, null, null))
                        .getMessage().contains("stream selector only"));
                assertTrue(service.logs("fixture", selector + " |= `absent`", start, end, null, null).contains("no matching lines."));
                assertEquals("3 lines match " + selector + " in " + LogText.window(new QueryTime.Range(base, base.plusSeconds(3)), ZoneOffset.UTC) + " (fixture).",
                        service.count("fixture", selector, start, end, null));
                var byShard = service.count("fixture", selector, start, end, "shard");
                assertTrue(byShard.endsWith("By shard:\n  a       2\n  b       1"), byShard);
                var byTime = service.count("fixture", selector, start, end, "time");
                assertTrue(byTime.startsWith("3 lines match"), byTime);
                assertTrue(byTime.contains("By time (1s buckets, bucket start):"), byTime);
                assertEquals(3, byTime.lines().filter(l -> l.matches("  \\d\\d:\\d\\d:\\d\\d +\\d+.*")).count(), byTime);
                assertTrue(service.count("fixture", "{fixture=\"absent\"}", start, end, "time").startsWith("0 lines match"));
                var summary = service.summarize("fixture", selector, start, end, null);
                assertTrue(summary.contains(": all 3 lines, spanning " + time + "–"), summary);
                assertTrue(summary.contains(", 3 distinct messages.\n"), summary);
                assertTrue(summary.contains("\n    1×  " + time + "  INFO  a  Ошибка 🐈\n         IOException: x\n"), summary); // root cause by simple type name
                assertTrue(summary.contains("\n    1×  " + time + "  -     b  same timestamp other stream\n"), summary);
                assertTrue(summary.endsWith("Counts are for the 3 sampled lines only; countLogs gives the number for the whole window. To read one group: queryLogs with |= \"<distinctive part of its message>\"."), summary);
                var bad = assertThrows(LokiOperationException.class, () -> service.logs("fixture", selector + " |= ", start, end, null, null));
                assertTrue(bad.error().message().startsWith("Loki rejected the query: "), bad.error().message());
                var discovery = new DiscoveryService(registry, client);
                var scoped = discovery.discover("fixture", selector, start, end, null);
                assertTrue(scoped.startsWith("Streams matching " + selector), scoped);
                assertTrue(scoped.contains("\n  shard: a, b\n"), scoped);
                assertTrue(scoped.contains("Line format (3 newest lines sampled): JSON 1, plain text 2.\nLevels seen: INFO.\n"), scoped);
                assertTrue(scoped.contains("JSON fields (after | json): error_stack_trace, log_level, message, service_name."), scoped);
                assertTrue(scoped.contains("| json | log_level=~\"(?i)error\""), scoped);
                var overview = discovery.discover("fixture", null, start, end, null);
                assertTrue(overview.startsWith("Labels in "), overview);
                assertTrue(overview.contains("\n  fixture: s04\n") && overview.contains("\n  shard: a, b\n"), overview);
                assertTrue(discovery.discover("fixture", "{fixture=\"absent\"}", start, end, null).contains("No lines sampled in this window"));
                assertEquals("Values of shard in streams matching " + selector + ", " + LogText.window(new QueryTime.Range(base, base.plusSeconds(3)), ZoneOffset.UTC)
                        + " (fixture): 2.\na\nb", discovery.discover("fixture", selector, start, end, "shard"));
                assertTrue(discovery.discover("fixture", null, start, end, "shard").startsWith("Values of shard, "));
            }
        }
    }
}
