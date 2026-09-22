package ru.it_spectrum.ai.loki.mcp.service;

import ru.it_spectrum.ai.loki.mcp.model.LogEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real lines from a stand, anonymised: {@code src/test/resources/fixtures/README.md} describes them.
 */
final class Fixtures {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Fixtures() {
    }

    /**
     * Events of a {@code .jsonl} fixture ({@code ts}, {@code labels}, {@code line} per line), in file order (chronological).
     */
    static List<LogEvent> events(String name) {
        try (var input = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (input == null) throw new IllegalArgumentException("no fixture " + name);
            var events = new ArrayList<LogEvent>();
            for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank()) continue;
                JsonNode node = MAPPER.readTree(line);
                var labels = new LinkedHashMap<String, String>();
                for (var label : node.get("labels").properties()) labels.put(label.getKey(), label.getValue().asString());
                events.add(new LogEvent(node.get("ts").asString(), labels, node.get("line").asString(), Map.of()));
            }
            return events;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The first fixture event whose line contains the text.
     */
    static LogEvent containing(List<LogEvent> events, String text) {
        return events.stream().filter(e -> e.line().contains(text)).findFirst().orElseThrow();
    }
}
