package ru.it_spectrum.ai.loki.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.*;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompactLogs(
        @Schema(requiredMode = REQUIRED) String connection,
        @Schema(requiredMode = REQUIRED) QueryResults.Window window,
        @Schema(requiredMode = REQUIRED) String direction,
        @Schema(requiredMode = REQUIRED) int limit,
        @Schema(requiredMode = REQUIRED) int readEntries,
        @Schema(requiredMode = REQUIRED) int returnedEntries,
        @Schema(requiredMode = REQUIRED) int resultStreams,
        @Schema(requiredMode = REQUIRED) boolean upstreamLimitReached,
        @Schema(requiredMode = REQUIRED) QueryResults.Completeness completeness,
        @Schema(requiredMode = REQUIRED) String continuationUnavailableReason,
        @Schema(requiredMode = NOT_REQUIRED, nullable = true) Long totalLinesProcessed,
        @Schema(requiredMode = REQUIRED) List<String> fields,
        @Schema(requiredMode = REQUIRED) List<String> limitations,
        @Schema(requiredMode = REQUIRED) List<Stream> streams,
        @Schema(requiredMode = REQUIRED) List<Event> events,
        @Schema(requiredMode = NOT_REQUIRED, nullable = true) String nextCursor,
        @Schema(requiredMode = REQUIRED) int upstreamFetchLimit,
        @Schema(requiredMode = REQUIRED) QueryResults.Window queryWindow) {
    public CompactLogs(String connection, QueryResults.Window window, String direction, int limit,
                       int readEntries, int returnedEntries, int resultStreams, boolean upstreamLimitReached,
                       QueryResults.Completeness completeness, String continuationUnavailableReason, Long totalLinesProcessed,
                       List<String> fields, List<String> limitations, List<Stream> streams, List<Event> events) {
        this(connection, window, direction, limit, readEntries, returnedEntries, resultStreams, upstreamLimitReached,
                completeness, continuationUnavailableReason, totalLinesProcessed, fields, limitations, streams, events,
                null, limit, window);
    }
    public CompactLogs {
        fields = List.copyOf(fields); limitations = List.copyOf(limitations);
        streams = List.copyOf(streams); events = List.copyOf(events);
    }
    public record Stream(
            @Schema(requiredMode = REQUIRED) String streamId,
            @Schema(requiredMode = REQUIRED) Map<String, String> resultLabels) {
        public Stream { resultLabels = Map.copyOf(resultLabels); }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Event(
            @Schema(requiredMode = REQUIRED) String timestampNanos,
            @Schema(requiredMode = REQUIRED) String streamId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String line,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Map<String, String> structuredMetadata,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) List<DiscoveryResult.NormalizedValue> normalized,
            @Schema(requiredMode = REQUIRED) List<String> truncatedFields,
            @Schema(requiredMode = REQUIRED) List<String> limitations) {
        public Event {
            if (structuredMetadata != null) structuredMetadata = Map.copyOf(structuredMetadata);
            if (normalized != null) normalized = List.copyOf(normalized);
            truncatedFields = List.copyOf(truncatedFields); limitations = List.copyOf(limitations);
        }
    }
}
