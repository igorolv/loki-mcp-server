package ru.it_spectrum.ai.loki.mcp.tools;

import java.util.Map;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import ru.it_spectrum.ai.loki.mcp.service.LogPagingService;
import tools.jackson.databind.json.JsonMapper;

/** Internal draft is stripped by the budgeted transport; it never appears on stdout. */
final class LogPageResults {
    private static final JsonMapper MAPPER = new JsonMapper();
    private LogPageResults() {}
    static CallToolResult result(LogPagingService.Draft draft) {
        @SuppressWarnings("unchecked") Map<String, Object> payload = MAPPER.convertValue(draft.result(), Map.class);
        return CallToolResult.builder().isError(false).structuredContent(payload)
                .addTextContent(MAPPER.writeValueAsString(draft.result()))
                .meta(Map.of(LogPagingService.DRAFT_META, draft)).build();
    }
}
