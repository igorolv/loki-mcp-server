package ru.it_spectrum.ai.loki.mcp.parser;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionsLoader;
import ru.it_spectrum.ai.loki.mcp.model.LogEvent;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JsonFormatTest {
    private final EventNormalizer normalizer = new EventNormalizer(
            ConnectionsLoader.loadFormats(Path.of("examples/log-formats.json")),
            ConnectionsLoader.loadJsonFormats(Path.of("examples/log-formats.json")));

    private NormalizedLogEvent view(Map<String, String> labels, String line) {
        return normalizer.normalize(new LogEvent("1", labels, line, Map.of()), List.of("app"));
    }

    @Test
    void recognizesSpringBootJsonShapesPerEvent() {
        var ecs = view(Map.of(), """
                {"@timestamp":"2026-09-28T00:00:00Z","log":{"level":"WARN","logger":"a.Ecs"},
                 "service":{"name":"ecs-app"},"message":"ecs line","ecs":{"version":"8.11"}}
                """);
        assertEquals("WARN", ecs.level());
        assertEquals("ecs-app", ecs.service());
        assertEquals("a.Ecs", ecs.logger());
        assertEquals("ecs line", ecs.message());

        var gelf = view(Map.of(), """
                {"version":"1.1","short_message":"gelf line","level":6,"_level_name":"INFO",
                 "_log_logger":"a.Gelf","full_message":"full detail"}
                """);
        assertEquals("INFO", gelf.level());
        assertEquals("a.Gelf", gelf.logger());
        assertEquals("gelf line", gelf.message());
        assertNull(gelf.stackTrace());

        var logstash = view(Map.of(), """
                {"@version":"1","logger_name":"a.Logstash","level":"ERROR","message":"logstash line"}
                """);
        assertEquals("ERROR", logstash.level());
        assertEquals("a.Logstash", logstash.logger());
        assertEquals("logstash line", logstash.message());
    }

    @Test
    void keepsLabelPriorityAndUsesGenericFallbackForUnknownJson() {
        var gelf = view(Map.of("app", "from-label", "level", "error"),
                "{\"version\":\"1.1\",\"short_message\":\"gelf line\",\"level\":6,\"_level_name\":\"INFO\"}");
        assertEquals("ERROR", gelf.level());
        assertEquals("from-label", gelf.service());
        assertEquals("gelf line", gelf.message());

        var unknown = view(Map.of(), "{\"format\":\"other\",\"message\":\"generic line\",\"level\":\"warn\"}");
        assertEquals("WARN", unknown.level());
        assertEquals("generic line", unknown.message());
        assertEquals(NormalizedLogEvent.Format.JSON, unknown.format());

        var plain = view(Map.of(), "2026-09-28T00:00:00Z  INFO 1 --- [main] a.Plain : plain line");
        assertEquals(NormalizedLogEvent.Format.PLAIN, plain.format());
        assertEquals("plain line", plain.message());
    }

    @Test
    void explicitMarkerSelectsFirstMatchingProfile() {
        var first = new JsonFormat("first", List.of("format"), Map.of("format", "x"),
                Map.of("message", List.of("one")));
        var second = new JsonFormat("second", List.of("format"), Map.of("format", "x"),
                Map.of("message", List.of("two")));
        var selected = new EventNormalizer(List.of(), List.of(first, second));
        var line = new LogEvent("1", Map.of(), "{\"format\":\"x\",\"one\":\"first\",\"two\":\"second\"}", Map.of());
        assertEquals("first", selected.normalize(line, List.of("app")).message());
    }
}
