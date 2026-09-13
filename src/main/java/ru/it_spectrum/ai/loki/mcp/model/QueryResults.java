package ru.it_spectrum.ai.loki.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.*;

public final class QueryResults {
    private QueryResults() {}
    public enum Completeness { COMPLETE, UNKNOWN, PARTIAL }
    public record Window(
            @Schema(requiredMode = REQUIRED) String startNanos,
            @Schema(requiredMode = REQUIRED) String endNanos) {}
    public record Event(
            @Schema(requiredMode = REQUIRED) String timestampNanos,
            @Schema(requiredMode = REQUIRED) Map<String, String> resultLabels,
            @Schema(requiredMode = REQUIRED) String line,
            @Schema(requiredMode = REQUIRED) Map<String, String> structuredMetadata) {
        public Event { resultLabels = Map.copyOf(resultLabels); structuredMetadata = Map.copyOf(structuredMetadata); }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Logs(
            @Schema(requiredMode = REQUIRED) String connection,
            @Schema(requiredMode = REQUIRED) Window window,
            @Schema(requiredMode = REQUIRED) String direction,
            @Schema(requiredMode = REQUIRED) int limit,
            @Schema(requiredMode = REQUIRED) int readEntries,
            @Schema(requiredMode = REQUIRED) int returnedEntries,
            @Schema(requiredMode = REQUIRED) int resultStreams,
            @Schema(requiredMode = REQUIRED) boolean upstreamLimitReached,
            @Schema(requiredMode = REQUIRED) Completeness completeness,
            @Schema(requiredMode = REQUIRED) String continuationUnavailableReason,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Long totalLinesProcessed,
            @Schema(requiredMode = REQUIRED) List<String> limitations,
            @Schema(requiredMode = REQUIRED) List<Event> events) {
        public Logs { limitations = List.copyOf(limitations); events = List.copyOf(events); }
    }
    public record Sample(
            @Schema(requiredMode = REQUIRED) BigDecimal timestampSeconds,
            @Schema(requiredMode = REQUIRED) String value) {}
    public record Series(
            @Schema(requiredMode = REQUIRED) Map<String, String> labels,
            @Schema(requiredMode = REQUIRED) List<Sample> samples) {
        public Series { labels = Map.copyOf(labels); samples = List.copyOf(samples); }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Metrics(
            @Schema(requiredMode = REQUIRED) String connection,
            @Schema(requiredMode = REQUIRED) String mode,
            @Schema(requiredMode = REQUIRED) Window window,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) BigDecimal stepSeconds,
            @Schema(requiredMode = REQUIRED) int seriesLimit,
            @Schema(requiredMode = REQUIRED) int pointLimit,
            @Schema(requiredMode = REQUIRED) int readSeries,
            @Schema(requiredMode = REQUIRED) int readPoints,
            @Schema(requiredMode = REQUIRED) int returnedSeries,
            @Schema(requiredMode = REQUIRED) int returnedPoints,
            @Schema(requiredMode = REQUIRED) Completeness completeness,
            @Schema(requiredMode = REQUIRED) String continuationUnavailableReason,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Long totalLinesProcessed,
            @Schema(requiredMode = REQUIRED) List<String> limitations,
            @Schema(requiredMode = REQUIRED) List<Series> series) {
        public Metrics { limitations = List.copyOf(limitations); series = List.copyOf(series); }
    }
}
