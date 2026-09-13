package ru.it_spectrum.ai.loki.mcp.model;

import io.swagger.v3.oas.annotations.media.Schema;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.*;

/** Safe, stable error payload. Never include request values or upstream exception messages. */
public record ToolError(
        @Schema(requiredMode = REQUIRED) ErrorCode code,
        @Schema(requiredMode = REQUIRED) String message,
        @Schema(requiredMode = REQUIRED) boolean retryable) {
}
