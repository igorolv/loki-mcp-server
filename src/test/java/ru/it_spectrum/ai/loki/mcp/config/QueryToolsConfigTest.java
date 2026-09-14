package ru.it_spectrum.ai.loki.mcp.config;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionAuth;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionDefinition;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionLimits;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.service.ConnectionsService;
import ru.it_spectrum.ai.loki.mcp.service.DiscoveryService;
import ru.it_spectrum.ai.loki.mcp.service.Errors;
import ru.it_spectrum.ai.loki.mcp.service.QueryService;

import java.net.URI;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryToolsConfigTest {
    private final ConnectionRegistry registry = new ConnectionRegistry(List.of(new ConnectionDefinition("test", null,
            URI.create("http://localhost:1"), ConnectionAuth.NONE, null, ZoneOffset.UTC, new ConnectionLimits(100, 100, 10000, 1024, 10, 3600))));
    private final QueryService service = mock(QueryService.class);
    private final List<io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification> specs =
            new QueryToolsConfig().queryToolSpecifications(service, mock(ConnectionsService.class), mock(DiscoveryService.class), registry);

    private CallToolResult call(String tool, Map<String, Object> args) {
        return specs.stream().filter(s -> s.tool().name().equals(tool)).findFirst().orElseThrow().callHandler().apply(null, new CallToolRequest(tool, args));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    @Test
    void toolsAreTextOnlyWithShortInstructionLikeDescriptions() {
        assertEquals(java.util.Set.of("queryLogs", "countLogs", "queryMetrics", "listConnections", "discoverLogs", "getLogContext", "summarizeLogs"), specs.stream().map(s -> s.tool().name()).collect(java.util.stream.Collectors.toSet()));
        for (var spec : specs) {
            assertNull(spec.tool().outputSchema(), spec.tool().name());
            assertTrue(spec.tool().description().length() < 700, spec.tool().name() + " description too long");
            assertTrue(spec.tool().annotations().readOnlyHint());
        }
    }

    @Test
    void unexpectedFailureIsSafeAndInvalidInputNeverInvokesService() {
        when(service.logs("test", "q", null, null, null, null)).thenThrow(new IllegalStateException("SECRET cause"));
        var result = call("queryLogs", Map.of("connection", "test", "query", "q"));
        assertTrue(result.isError());
        assertEquals("Error INTERNAL_ERROR: Operation failed internally.", text(result));
        assertNull(result.structuredContent());
        clearInvocations(service);
        var invalid = call("queryLogs", Map.of("connection", "test", "query", "q", "limit", "SECRET"));
        assertTrue(invalid.isError());
        assertTrue(text(invalid).startsWith("Error INVALID_ARGUMENT: Argument types are wrong."));
        assertFalse(invalid.toString().contains("SECRET"));
        var missing = call("queryLogs", Map.of("query", "q"));
        assertTrue(text(missing).startsWith("Error CONNECTION_REQUIRED"));
        var unknown = call("queryLogs", Map.of("connection", "nope", "query", "q"));
        assertTrue(text(unknown).startsWith("Error UNKNOWN_CONNECTION"));
        verifyNoInteractions(service);
    }

    @Test
    void safeErrorsFromServicesAndOversizedTextAreReportedAsText() {
        when(service.count("test", "{a=\"b\"}", null, null, null)).thenThrow(Errors.invalid("groupBy must be a label name."));
        var error = call("countLogs", Map.of("connection", "test", "query", "{a=\"b\"}"));
        assertTrue(error.isError());
        assertEquals("Error INVALID_ARGUMENT: groupBy must be a label name.", text(error));
        when(service.logs("test", "q", null, null, null, null)).thenReturn("x".repeat(2000));
        var oversized = call("queryLogs", Map.of("connection", "test", "query", "q"));
        assertTrue(oversized.isError());
        assertTrue(text(oversized).startsWith("Error RESPONSE_BUDGET_EXCEEDED"));
        when(service.logs("test", "q", null, null, null, null)).thenReturn("fine");
        var ok = call("queryLogs", Map.of("connection", "test", "query", "q"));
        assertFalse(ok.isError());
        assertEquals("fine", text(ok));
    }
}
