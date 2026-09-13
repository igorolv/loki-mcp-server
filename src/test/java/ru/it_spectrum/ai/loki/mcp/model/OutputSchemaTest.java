package ru.it_spectrum.ai.loki.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.method.tool.utils.McpJsonSchemaGenerator;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
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
}
