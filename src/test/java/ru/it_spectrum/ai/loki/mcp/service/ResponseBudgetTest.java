package ru.it_spectrum.ai.loki.mcp.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.modelcontextprotocol.spec.McpSchema.*;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import org.springframework.ai.mcp.annotation.method.tool.utils.McpJsonSchemaGenerator;
import java.net.URI;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import ru.it_spectrum.ai.loki.mcp.connection.*;
import ru.it_spectrum.ai.loki.mcp.model.*;
import ru.it_spectrum.ai.loki.mcp.model.QueryResults.*;
import static org.junit.jupiter.api.Assertions.*;

class ResponseBudgetTest {
    private final JsonMapper mapper = JsonMapper.builder().changeDefaultPropertyInclusion(
            ignored -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL)).build();
    private ResponseBudget budget(int maximum) {
        return new ResponseBudget(mapper, new ConnectionRegistry(List.of(
                new ConnectionDefinition("test", null, URI.create("http://localhost"), ConnectionAuth.NONE, null,
                        ZoneOffset.UTC, new ConnectionLimits(100, 100, 1000000, maximum, 100, 3600)))));
    }
    private JSONRPCResponse response(Object value, Object id) {
        @SuppressWarnings("unchecked") Map<String, Object> payload = mapper.convertValue(value, Map.class);
        return JSONRPCResponse.result(id, CallToolResult.builder().isError(false).structuredContent(payload)
                .addTextContent(mapper.writeValueAsString(value)).build());
    }
    private tools.jackson.databind.JsonNode check(JSONRPCResponse value, int maximum, Class<?> type) {
        assertTrue(mapper.writeValueAsBytes(value).length + 1 <= maximum);
        var result = (CallToolResult) value.result();
        var payload = mapper.readTree(mapper.writeValueAsString(result.structuredContent()));
        assertEquals(payload, mapper.readTree(((TextContent) result.content().getFirst()).text()));
        @SuppressWarnings("unchecked") Map<String, Object> schema = mapper.readValue(
                McpJsonSchemaGenerator.generateFromClass(Boolean.TRUE.equals(result.isError()) ? ToolError.class : type), Map.class);
        var validation = new DefaultJsonSchemaValidator().validate(schema, result.structuredContent());
        assertTrue(validation.valid(), validation.errorMessage());
        return payload;
    }
    @Test void fullEnvelopeAndEscapedTextCountAndExactBoundary() {
        var logs = ResponseProjection.project(ResponseProjectionTest.logs(List.of(
                new Event("1", Map.of("job", "🐈\"\\\n"), "ошибка\"\\\n🐈", Map.of()))), List.of("line"));
        var response = response(logs, "🐈\"\\".repeat(15));
        int exact = mapper.writeValueAsBytes(response).length + 1;
        assertSame(response, budget(exact).fit(response));
        var smaller = budget(exact - 1).fit(response);
        check(smaller, exact - 1, CompactLogs.class);
        assertNotEquals(mapper.valueToTree(response), mapper.valueToTree(smaller));
    }
    @Test void dictionaryPrunedCountsAndPrefixKeepRealDuplicates() {
        var events = new ArrayList<Event>();
        for (int i = 0; i < 30; i++) events.add(new Event("1", Map.of("job", "s" + i / 3), "🐈".repeat(800), Map.of()));
        var original = ResponseProjection.project(ResponseProjectionTest.logs(events), List.of("line"));
        var payload = check(budget(12000).fit(response(original, 1)), 12000, CompactLogs.class);
        assertEquals(30, payload.path("readEntries").asInt());
        assertEquals("PARTIAL", payload.path("completeness").asText());
        assertEquals(payload.path("events").size(), payload.path("returnedEntries").asInt());
        assertTrue(payload.path("events").size() > 0);
        var used = new HashSet<String>();
        payload.path("events").forEach(e -> used.add(e.path("streamId").asText()));
        assertEquals(used.size(), payload.path("streams").size());
        assertEquals(30, original.events().size());
    }
    @Test void minimumBudgetAndOversizedServiceFieldsYieldSafeTypedError() {
        var logs = ResponseProjection.project(ResponseProjectionTest.logs(List.of()), List.of());
        var payload = check(budget(1024).fit(response(logs, 1)), 1024, CompactLogs.class);
        assertEquals("RESPONSE_BUDGET_EXCEEDED", payload.path("code").asText());
        var huge = new DiscoveryResult("test", "SECRET".repeat(10000), new Window("1", "2"),
                new DiscoveryResult.Coverage(0, 0, 10, 0, 0, Completeness.COMPLETE, false),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        payload = check(budget(1024).fit(response(huge, "id")), 1024, DiscoveryResult.class);
        assertEquals("RESPONSE_BUDGET_EXCEEDED", payload.path("code").asText());
        assertFalse(payload.toString().contains("SECRET"));
        assertThrows(IllegalStateException.class, () -> budget(1024).fit(response(logs, "x".repeat(2000))));
    }
    @Test void metricSamplesAreNeverShortenedAndCountsReflectRemovedPoints() {
        var samples = new ArrayList<Sample>();
        for (int i = 0; i < 100; i++) samples.add(new Sample(new BigDecimal("1700000000.123456789"), "NaN"));
        var metrics = new Metrics("test", "range", new Window("1", "2"), BigDecimal.ONE, 10, 100, 1, 100,
                1, 100, Completeness.COMPLETE, "CURSORS_NOT_IMPLEMENTED", null, List.of(), List.of(new Series(Map.of(), samples)));
        var payload = check(budget(3000).fit(response(metrics, 1)), 3000, Metrics.class);
        assertEquals(100, payload.path("readPoints").asInt());
        assertEquals(payload.path("series").get(0).path("samples").size(), payload.path("returnedPoints").asInt());
        assertEquals("NaN", payload.path("series").get(0).path("samples").get(0).path("value").asText());
        assertTrue(mapper.writeValueAsString(budget(3000).fit(response(metrics, 1))).contains("1700000000.123456789"));
    }
    @Test void discoveryShortensExamplesIncludingNormalizedAndReportsLocalTruncation() {
        var example = new DiscoveryResult.Example(new Event("1", Map.of(), "🐈".repeat(10000), Map.of()),
                DiscoveryResult.Format.JSON_OBJECT, List.of(new DiscoveryResult.NormalizedValue("message",
                DiscoveryResult.Origin.LINE_JSON, "/message", "secret".repeat(10000))), List.of());
        var discovery = new DiscoveryResult("test", "{job=\"test\"}", new Window("1", "2"),
                new DiscoveryResult.Coverage(1, 1, 10, 1, 1, Completeness.COMPLETE, false),
                List.of(), List.of(new DiscoveryResult.Label("job", List.of("test"), false)), List.of(), List.of(), List.of(example), List.of());
        var payload = check(budget(3000).fit(response(discovery, 1)), 3000, DiscoveryResult.class);
        assertEquals(1, payload.path("examples").size());
        assertTrue(payload.path("examples").get(0).path("limitations").toString().contains("EXAMPLE_FIELDS_TRUNCATED"));
        assertTrue(payload.path("examples").get(0).path("event").path("line").asText().contains("[truncated]"));
        assertTrue(payload.path("coverage").path("localTruncation").asBoolean());
        assertEquals(1, payload.path("coverage").path("entriesExamined").asInt());
    }

    @Test void largeLabelsMetadataAndDiscoveryFieldsCannotBypassBudget() {
        var event = new Event("1", Map.of("label", "🐈".repeat(10000)), "short", Map.of("trace", "x".repeat(10000)));
        var logs = ResponseProjection.project(ResponseProjectionTest.logs(List.of(event)), List.of("structuredMetadata"));
        var payload = check(budget(2000).fit(response(logs, 1)), 2000, CompactLogs.class);
        assertEquals(0, payload.path("events").size());
        assertEquals(0, payload.path("streams").size());
        assertEquals("PARTIAL", payload.path("completeness").asText());
        var discovery = new DiscoveryResult("test", "{job=\"test\"}", new Window("1", "2"),
                new DiscoveryResult.Coverage(1, 1, 10, 1, 1, Completeness.COMPLETE, false),
                List.of(), List.of(new DiscoveryResult.Label("job", List.of("🐈".repeat(10000)), false)),
                List.of(new DiscoveryResult.Field(DiscoveryResult.Origin.LINE_JSON, "/" + "x".repeat(10000), List.of("string"), 1)),
                List.of(), List.of(), List.of());
        payload = check(budget(2000).fit(response(discovery, 1)), 2000, DiscoveryResult.class);
        assertEquals(0, payload.path("fields").size());
        assertEquals(0, payload.path("streamLabels").size());
        assertTrue(payload.path("coverage").path("localTruncation").asBoolean());
    }
}
