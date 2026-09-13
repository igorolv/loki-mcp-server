package ru.it_spectrum.ai.loki.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.method.tool.utils.McpJsonSchemaGenerator;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import static ru.it_spectrum.ai.loki.mcp.model.QueryResults.*;
import static org.junit.jupiter.api.Assertions.*;

class OutputSchemaTest {
    private final JsonMapper mapper = JsonMapper.builder().changeDefaultPropertyInclusion(
            ignored -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL)).build();

    @Test void publicRecordsValidateWithOptionalFieldsAbsentAndPresent() {
        validate(new ListConnectionsResult(List.of(new ConnectionSummary("one", null), new ConnectionSummary("two", "Test"))));
        validate(new ListConnectionsResult(List.of()));
        for (var code : ErrorCode.values()) validate(new ToolError(code, "Safe message", false));
        String schema = McpJsonSchemaGenerator.generateFromClass(ConnectionSummary.class);
        var required = mapper.readTree(schema).path("required");
        assertEquals(List.of("name"), mapper.convertValue(required, List.class));
        assertFalse(mapper.writeValueAsString(new ConnectionSummary("one", null)).contains("description"));
    }

    private void validate(Object value) {
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = mapper.readValue(McpJsonSchemaGenerator.generateFromClass(value.getClass()), Map.class);
        var result = new DefaultJsonSchemaValidator().validate(schema,
                mapper.readValue(mapper.writeValueAsString(value), Object.class));
        assertTrue(result.valid(), result.errorMessage());
    }

    @Test void queryRecordsValidatePopulatedAndEmptyWithOptionalStats() {
        var window = new Window("1700000000123456789", "1700000001123456789");
        for (Long scanned : new Long[]{null, 200L}) {
            validate(new Logs("test", window, "forward", 10, 1, 1, 1, false, Completeness.COMPLETE,
                    "CURSORS_NOT_IMPLEMENTED", scanned, List.of(), List.of(new Event(window.startNanos(),
                    Map.of("key", "value"), "Ошибка 🐈", Map.of("trace", "x")))));
            validate(new Logs("test", window, "backward", 10, 0, 0, 0, false, Completeness.COMPLETE,
                    "CURSORS_NOT_IMPLEMENTED", scanned, List.of(), List.of()));
            validate(new Metrics("test", "range", window, new BigDecimal("0.125"), 10, 100, 1, 1, 1, 1,
                    Completeness.COMPLETE, "CURSORS_NOT_IMPLEMENTED", scanned, List.of(),
                    List.of(new Series(Map.of(), List.of(new Sample(new BigDecimal("1700000000.125"), "+Inf"))))));
            validate(new Metrics("test", "instant", window, null, 10, 100, 0, 0, 0, 0,
                    Completeness.COMPLETE, "CURSORS_NOT_IMPLEMENTED", scanned, List.of(), List.of()));
        }
    }

    @Test void discoverySchemaValidatesEmptyAndPopulatedIncludingNullableCapabilities() {
        var window = new Window("1", "2");
        var coverage = new DiscoveryResult.Coverage(1, 1, 20, 1, 1, Completeness.COMPLETE, false);
        validate(new DiscoveryResult("test", "{job=\"test\"}", window, coverage, List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        validate(new DiscoveryResult("test", "{job=\"test\"}", window, coverage,
                List.of(new DiscoveryResult.Capability("series", DiscoveryResult.Availability.AVAILABLE, null),
                        new DiscoveryResult.Capability("query_range", DiscoveryResult.Availability.UNKNOWN, ErrorCode.UPSTREAM_TIMEOUT)),
                List.of(new DiscoveryResult.Label("job", List.of("test"), true)),
                List.of(new DiscoveryResult.Field(DiscoveryResult.Origin.LINE_JSON, "/message", List.of("string", "null"), 1)),
                List.of(new DiscoveryResult.FormatCount(DiscoveryResult.Format.JSON_OBJECT, 1)),
                List.of(new DiscoveryResult.Example(new Event("1", Map.of(), "{}", Map.of()), DiscoveryResult.Format.JSON_OBJECT,
                        List.of(new DiscoveryResult.NormalizedValue("service", DiscoveryResult.Origin.LINE_JSON, "/service.name", "backend")), List.of())), List.of()));
    }
}
