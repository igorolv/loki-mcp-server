package ru.it_spectrum.ai.loki.mcp.service;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import java.util.HashSet;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionLimits;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;
import ru.it_spectrum.ai.loki.mcp.model.ToolError;

/** Measures the real duplicated MCP payload, JSON-RPC envelope and newline in UTF-8.
 * Tree edits are internal projections of the declared DTOs, not a separate public contract. */
public final class ResponseBudget {
    private final JsonMapper mapper;
    private final ConnectionRegistry registry;
    public ResponseBudget(JsonMapper mapper, ConnectionRegistry registry) {
        this.mapper = mapper; this.registry = registry;
    }

    public JSONRPCResponse fit(JSONRPCResponse response) {
        if (!(response.result() instanceof CallToolResult result)) return response;
        var payload = (ObjectNode) mapper.valueToTree(result.structuredContent());
        var draft = result.meta() != null && result.meta().get(LogPagingService.DRAFT_META) instanceof LogPagingService.Draft page ? page : null;
        int maximum = Boolean.TRUE.equals(result.isError()) ? ConnectionLimits.MIN_RESPONSE_BYTES : ConnectionLimits.DEFAULTS.maxResponseBytes();
        if (payload.has("connection")) maximum = registry.require(payload.path("connection").asText()).limits().maxResponseBytes();
        var initial = draft == null ? response : pageResponse(response.id(), payload, draft);
        if (size(initial) <= maximum) return initial;
        if (!Boolean.TRUE.equals(result.isError())) {
            while (shortenText(payload)) {
                var candidate = pageResponse(response.id(), payload, draft);
                if (size(candidate) <= maximum) return candidate;
            }
            markTruncated(payload);
            while (reduce(payload)) {
                var candidate = pageResponse(response.id(), payload, draft);
                if (size(candidate) <= maximum) return candidate;
            }
        }
        var error = mapper.<ObjectNode>valueToTree(new ToolError(ErrorCode.RESPONSE_BUDGET_EXCEEDED,
                "Response cannot fit. Reduce fields or scope, or increase maxResponseBytes.", false));
        var fallback = response(response.id(), error, true);
        // A caller-controlled ID alone can exceed the entire budget. Never emit an oversized response.
        if (size(fallback) > maximum) throw new IllegalStateException("JSON-RPC id leaves no room for a bounded response.");
        return fallback;
    }

    public int size(JSONRPCResponse response) { return mapper.writeValueAsBytes(response).length + 1; }

    private JSONRPCResponse pageResponse(Object id, ObjectNode payload, LogPagingService.Draft draft) {
        if (draft != null) draft.finish(payload);
        return response(id, payload, false);
    }

    private JSONRPCResponse response(Object id, ObjectNode payload, boolean error) {
        @SuppressWarnings("unchecked") Map<String, Object> structured = mapper.convertValue(payload, Map.class);
        return JSONRPCResponse.result(id, CallToolResult.builder().isError(error).structuredContent(structured)
                .addTextContent(mapper.writeValueAsString(payload)).build());
    }

    private static void markTruncated(ObjectNode payload) {
        if (payload.path("limitations") instanceof ArrayNode limitations) add(limitations, "RESPONSE_BYTE_BUDGET");
        if (payload.has("completeness")) payload.put("completeness", "PARTIAL");
        if (payload.path("coverage") instanceof ObjectNode coverage) coverage.put("localTruncation", true);
    }

    private static boolean shortenText(ObjectNode payload) {
        boolean changed = false;
        for (var event : payload.path("events")) changed |= shortenEvent((ObjectNode) event, false);
        for (var example : payload.path("examples")) {
            boolean shortened = shortenEvent((ObjectNode) example.path("event"), true);
            for (var normalized : example.path("normalized")) shortened |= shortenValue((ObjectNode) normalized, "value");
            if (shortened) {
                add((ArrayNode) example.path("limitations"), "EXAMPLE_FIELDS_TRUNCATED");
                changed = true;
            }
        }
        if (changed) {
            add((ArrayNode) payload.path("limitations"), "EVENT_FIELDS_TRUNCATED");
            add((ArrayNode) payload.path("limitations"), "RESPONSE_BYTE_BUDGET");
            if (payload.path("coverage") instanceof ObjectNode coverage) coverage.put("localTruncation", true);
        }
        return changed;
    }

    private static boolean shortenEvent(ObjectNode event, boolean example) {
        boolean changed = shortenValue(event, "line");
        if (changed && !example) add((ArrayNode) event.path("truncatedFields"), "line");
        for (var normalized : event.path("normalized")) {
            if (shortenValue((ObjectNode) normalized, "value")) {
                add((ArrayNode) event.path("truncatedFields"), "normalized:" + normalized.path("path").asText());
                changed = true;
            }
        }
        return changed;
    }

    private static boolean shortenValue(ObjectNode parent, String field) {
        if (!parent.path(field).isString()) return false;
        String text = parent.path(field).asText();
        int length = text.codePointCount(0, text.length());
        // Include the abbreviation marker in the stopping threshold to guarantee termination.
        if (length <= ResponseProjection.MIN_TEXT_CODE_POINTS + 32) return false;
        parent.put(field, ResponseProjection.abbreviate(text, Math.max(ResponseProjection.MIN_TEXT_CODE_POINTS, length / 2)));
        return true;
    }

    private static void add(ArrayNode array, String value) {
        for (var existing : array) if (existing.asText().equals(value)) return;
        array.add(value);
    }

    private static boolean reduce(ObjectNode payload) {
        if (payload.path("events") instanceof ArrayNode events && !events.isEmpty()) {
            halve(events);
            payload.put("returnedEntries", events.size());
            var used = new HashSet<String>();
            events.forEach(e -> used.add(e.path("streamId").asText()));
            var streams = (ArrayNode) payload.path("streams");
            for (int i = streams.size() - 1; i >= 0; i--) {
                if (!used.contains(streams.get(i).path("streamId").asText())) streams.remove(i);
            }
            return true;
        }
        if (payload.path("series") instanceof ArrayNode series && !series.isEmpty()) {
            if (series.size() > 1) halve(series);
            else {
                var samples = (ArrayNode) series.get(0).path("samples");
                halve(samples);
                if (samples.isEmpty()) series.removeAll();
            }
            int points = 0;
            for (var item : series) points += item.path("samples").size();
            payload.put("returnedSeries", series.size()); payload.put("returnedPoints", points);
            return true;
        }
        for (String field : new String[]{"examples", "fields", "streamLabels", "formats"}) {
            // 'fields' in queryLogs describes the projection and must not be removed.
            if (payload.has("coverage") && payload.path(field) instanceof ArrayNode array && !array.isEmpty()) {
                halve(array); return true;
            }
        }
        return false;
    }

    private static void halve(ArrayNode array) {
        int keep = array.size() / 2;
        while (array.size() > keep) array.remove(array.size() - 1);
    }
}
