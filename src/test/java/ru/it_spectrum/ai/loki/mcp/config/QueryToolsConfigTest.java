package ru.it_spectrum.ai.loki.mcp.config;

import java.util.Map;
import org.junit.jupiter.api.Test;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import org.springframework.ai.mcp.annotation.method.tool.utils.McpJsonSchemaGenerator;
import ru.it_spectrum.ai.loki.mcp.model.ToolError;
import ru.it_spectrum.ai.loki.mcp.service.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryToolsConfigTest {
    @Test void unexpectedFailureIsSafeAndSchemaValidAndInvalidInputNeverInvokesService() {
        var service = mock(QueryService.class);
        var paging = mock(LogPagingService.class);
        when(paging.first("test", "q", "now-1s", "now", null, null, null)).thenThrow(new IllegalStateException("SECRET cause"));
        var spec = new QueryToolsConfig().queryToolSpecifications(service, mock(ConnectionsService.class), mock(DiscoveryService.class), paging).stream()
                .filter(s -> s.tool().name().equals("queryLogs")).findFirst().orElseThrow();
        var result = spec.callHandler().apply(null, new CallToolRequest("queryLogs",
                Map.of("connection", "test", "query", "q", "start", "now-1s", "end", "now")));
        assertTrue(result.isError());
        assertEquals("INTERNAL_ERROR", ((Map<?, ?>) result.structuredContent()).get("code"));
        assertFalse(result.toString().contains("SECRET"));
        var mapper = new JsonMapper();
        @SuppressWarnings("unchecked") Map<String, Object> schema = mapper.readValue(McpJsonSchemaGenerator.generateFromClass(ToolError.class), Map.class);
        assertTrue(new DefaultJsonSchemaValidator().validate(schema, result.structuredContent()).valid());
        clearInvocations(service, paging);
        var invalid = spec.callHandler().apply(null, new CallToolRequest("queryLogs",
                Map.of("connection", "test", "query", "q", "start", "now-1s", "end", "now", "limit", "SECRET")));
        assertEquals("INVALID_ARGUMENT", ((Map<?, ?>) invalid.structuredContent()).get("code"));
        assertFalse(invalid.toString().contains("SECRET"));
        verifyNoInteractions(service, paging);
    }
}
