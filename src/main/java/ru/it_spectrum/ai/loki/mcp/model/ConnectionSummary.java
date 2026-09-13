package ru.it_spectrum.ai.loki.mcp.model;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonInclude;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.*;

/** Public allowlist: transport settings and credentials must never enter this DTO. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConnectionSummary(
        @Schema(requiredMode = REQUIRED) String name,
        @Schema(requiredMode = NOT_REQUIRED, nullable = true) String description) {
}
