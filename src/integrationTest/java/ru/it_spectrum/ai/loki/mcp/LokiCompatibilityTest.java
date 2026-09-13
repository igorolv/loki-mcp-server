package ru.it_spectrum.ai.loki.mcp;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.connection.*;
import ru.it_spectrum.ai.loki.mcp.service.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.it_spectrum.ai.loki.mcp.model.QueryResults.Completeness.*;

class LokiCompatibilityTest {
    @ParameterizedTest @ValueSource(strings = {"2.6.1", "3.6.0"}) @Timeout(240)
    void logsAndMetricsAgainstIsolatedLoki(String version) throws Exception {
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
            var registry = new ConnectionRegistry(List.of(new ConnectionDefinition("fixture", null, url,
                    ConnectionAuth.NONE, null, ZoneOffset.UTC, ConnectionLimits.DEFAULTS)));
            // Only this container-derived URL can be used for ingestion; no external config or live URLs.
            Instant base = Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            String a = QueryTime.nanos(base.plusNanos(123456789)), b = QueryTime.nanos(base.plusSeconds(1).plusNanos(987654321));
            String body = """
                    {"streams":[
                      {"stream":{"fixture":"s04","shard":"a"},"values":[["%s","Ошибка 🐈"],["%s","second"]]},
                      {"stream":{"fixture":"s04","shard":"b"},"values":[["%s","same timestamp other stream"]]}
                    ]}
                    """.formatted(a, b, a);
            try (var http = HttpClient.newHttpClient(); var client = new LokiHttpClient(registry)) {
                var pushed = http.send(HttpRequest.newBuilder(url.resolve("/loki/api/v1/push"))
                        .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(204, pushed.statusCode(), pushed.body());
                var service = new QueryService(registry, client);
                String start = base.toString(), end = base.plusSeconds(3).toString(), selector = "{fixture=\"s04\"}";
                var logs = service.logs("fixture", selector, start, end, "forward", 10);
                assertEquals(3, logs.readEntries()); assertEquals(3, logs.returnedEntries());
                assertEquals(2, logs.resultStreams()); assertEquals(COMPLETE, logs.completeness());
                assertEquals(List.of(a, a, b), logs.events().stream().map(e -> e.timestampNanos()).toList());
                assertTrue(logs.events().stream().anyMatch(e -> e.line().equals("Ошибка 🐈")));
                var limited = service.logs("fixture", selector, start, end, "backward", 2);
                assertEquals(2, limited.returnedEntries()); assertEquals(UNKNOWN, limited.completeness());
                assertEquals(b, limited.events().getFirst().timestampNanos());
                assertEquals(0, service.logs("fixture", selector + " |= `absent`", start, end, null, 10).returnedEntries());
                String metric = "sum(count_over_time(" + selector + "[10s]))";
                var instant = service.metrics("fixture", metric, "instant", null, null, end, null, null, null);
                assertEquals(1, instant.returnedSeries()); assertEquals(1, instant.returnedPoints());
                assertEquals(0, new BigDecimal("3").compareTo(new BigDecimal(instant.series().getFirst().samples().getFirst().value())));
                assertEquals(0, BigDecimal.valueOf(base.plusSeconds(3).getEpochSecond())
                        .compareTo(instant.series().getFirst().samples().getFirst().timestampSeconds()));
                var range = service.metrics("fixture", metric, "range", base.plusSeconds(2).toString(), end,
                        null, BigDecimal.ONE, null, null);
                assertEquals(1, range.returnedSeries()); assertEquals(2, range.returnedPoints());
                for (var sample : range.series().getFirst().samples()) assertEquals(0, new BigDecimal("3").compareTo(new BigDecimal(sample.value())));
                String empty = "sum(count_over_time({fixture=\"absent\"}[10s]))";
                assertEquals(0, service.metrics("fixture", empty, "instant", null, null, end, null, null, null).returnedPoints());
                assertEquals(0, service.metrics("fixture", empty, "range", start, end, null, BigDecimal.ONE, null, null).returnedPoints());
                var discovery = new DiscoveryService(registry, client);
                var discovered = discovery.discover("fixture", selector, start, end, 10);
                assertEquals(2, discovered.coverage().seriesRead());
                assertEquals(3, discovered.coverage().entriesExamined());
                assertEquals(COMPLETE, discovered.coverage().sampleCompleteness());
                // Loki 3.x may add service_name during ingestion; discover actual labels rather than assume their absence.
                assertTrue(discovered.streamLabels().stream().map(l -> l.name()).toList().containsAll(List.of("fixture", "shard")));
                assertEquals(List.of("a", "b"), discovered.streamLabels().stream().filter(l -> l.name().equals("shard")).findFirst().orElseThrow().observedValues());
                assertTrue(discovered.capabilities().subList(0, 2).stream().allMatch(c -> c.availability()
                        == ru.it_spectrum.ai.loki.mcp.model.DiscoveryResult.Availability.AVAILABLE));
                assertEquals(UNKNOWN, discovery.discover("fixture", selector, start, end, 2).coverage().sampleCompleteness());
                assertEquals(0, discovery.discover("fixture", "{fixture=\"absent\"}", start, end, 10).coverage().entriesExamined());
                String ecs = "{\"service.name\":\"backend\",\"log\":{\"level\":\"ERROR\"},\"message\":\"Ошибка 🐈\"}";
                String mixedBody = new tools.jackson.databind.json.JsonMapper().writeValueAsString(java.util.Map.of("streams", List.of(
                        java.util.Map.of("stream", java.util.Map.of("fixture", "s05"), "values", List.of(List.of(a, ecs), List.of(b, "plain"))))));
                var mixedPush = http.send(HttpRequest.newBuilder(url.resolve("/loki/api/v1/push"))
                        .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mixedBody)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(204, mixedPush.statusCode(), mixedPush.body());
                var mixed = discovery.discover("fixture", "{fixture=\"s05\"}", start, end, 10);
                assertEquals(2, mixed.coverage().entriesExamined());
                assertEquals(2, mixed.formats().size());
                assertTrue(mixed.fields().stream().anyMatch(f -> f.path().equals("/log/level") && f.observedEntries() == 1));
                assertTrue(mixed.examples().stream().anyMatch(e -> e.event().line().equals(ecs)
                        && e.normalized().stream().anyMatch(v -> v.name().equals("service") && v.value().equals("backend"))));
            }
        }
    }
}
