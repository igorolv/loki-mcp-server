package ru.it_spectrum.ai.loki.mcp.tools;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.spring.SyncMcpAnnotationProviders;
import ru.it_spectrum.ai.loki.mcp.service.ConnectionsService;
import ru.it_spectrum.ai.loki.mcp.service.DiscoveryService;
import ru.it_spectrum.ai.loki.mcp.service.ExportService;
import ru.it_spectrum.ai.loki.mcp.service.QueryService;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ToolDefinitionsTest {
    private final List<SyncToolSpecification> specs = SyncMcpAnnotationProviders.toolSpecifications(List.of(
            new QueryTools(mock(QueryService.class)), new ConnectionTools(mock(ConnectionsService.class)),
            new DiscoveryTools(mock(DiscoveryService.class)), new ExportTools(mock(ExportService.class))));

    @Test
    void toolsAreTextOnlyWithShortInstructionLikeDescriptions() {
        assertEquals(Set.of("queryLogs", "countLogs", "listConnections", "discoverLogs", "exportLogs"),
                specs.stream().map(s -> s.tool().name()).collect(Collectors.toSet()));
        for (var spec : specs) {
            assertNull(spec.tool().outputSchema(), spec.tool().name());
            assertTrue(spec.tool().description().length() < 700, spec.tool().name() + " description too long");
            // exportLogs writes files on the local disk; every other tool only reads.
            assertEquals(!spec.tool().name().equals("exportLogs"), spec.tool().annotations().readOnlyHint(), spec.tool().name());
            @SuppressWarnings("unchecked")
            var required = (List<String>) spec.tool().inputSchema().get("required");
            assertEquals(!spec.tool().name().equals("listConnections"), required != null && required.contains("connection"),
                    spec.tool().name());
        }
    }
}
