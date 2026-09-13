package ru.it_spectrum.ai.loki.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.*;

public record DiscoveryResult(
        @Schema(requiredMode = REQUIRED) String connection,
        @Schema(requiredMode = REQUIRED) String selector,
        @Schema(requiredMode = REQUIRED) QueryResults.Window window,
        @Schema(requiredMode = REQUIRED) Coverage coverage,
        @Schema(requiredMode = REQUIRED) List<Capability> capabilities,
        @Schema(requiredMode = REQUIRED) List<Label> streamLabels,
        @Schema(requiredMode = REQUIRED) List<Field> fields,
        @Schema(requiredMode = REQUIRED) List<FormatCount> formats,
        @Schema(requiredMode = REQUIRED) List<Example> examples,
        @Schema(requiredMode = REQUIRED) List<String> limitations) {
    public DiscoveryResult {
        capabilities = List.copyOf(capabilities); streamLabels = List.copyOf(streamLabels);
        fields = List.copyOf(fields); formats = List.copyOf(formats);
        examples = List.copyOf(examples); limitations = List.copyOf(limitations);
    }
    public enum Availability { AVAILABLE, UNAVAILABLE_AT_PATH, UNKNOWN }
    public enum Origin { RESULT_LABEL, STRUCTURED_METADATA, LINE_JSON }
    public enum Format { JSON_OBJECT, JSON_VALUE, PLAIN_TEXT, UNPARSED }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Capability(
            @Schema(requiredMode = REQUIRED) String endpoint,
            @Schema(requiredMode = REQUIRED) Availability availability,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) ErrorCode errorCode) {}
    public record Coverage(
            @Schema(requiredMode = REQUIRED) int seriesRead,
            @Schema(requiredMode = REQUIRED) int seriesExamined,
            @Schema(requiredMode = REQUIRED) int sampleLimit,
            @Schema(requiredMode = REQUIRED) int entriesRead,
            @Schema(requiredMode = REQUIRED) int entriesExamined,
            @Schema(requiredMode = REQUIRED) QueryResults.Completeness sampleCompleteness,
            @Schema(requiredMode = REQUIRED) boolean localTruncation) {}
    public record Label(
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) List<String> observedValues,
            @Schema(requiredMode = REQUIRED) boolean valuesTruncated) {
        public Label { observedValues = List.copyOf(observedValues); }
    }
    public record Field(
            @Schema(requiredMode = REQUIRED) Origin origin,
            @Schema(requiredMode = REQUIRED) String path,
            @Schema(requiredMode = REQUIRED) List<String> types,
            @Schema(requiredMode = REQUIRED) int observedEntries) {
        public Field { types = List.copyOf(types); }
    }
    public record FormatCount(
            @Schema(requiredMode = REQUIRED) Format format,
            @Schema(requiredMode = REQUIRED) int entries) {}
    public record NormalizedValue(
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) Origin origin,
            @Schema(requiredMode = REQUIRED) String path,
            @Schema(requiredMode = REQUIRED) String value) {}
    public record Example(
            @Schema(requiredMode = REQUIRED) QueryResults.Event event,
            @Schema(requiredMode = REQUIRED) Format format,
            @Schema(requiredMode = REQUIRED) List<NormalizedValue> normalized,
            @Schema(requiredMode = REQUIRED) List<String> limitations) {
        public Example { normalized = List.copyOf(normalized); limitations = List.copyOf(limitations); }
    }
}
