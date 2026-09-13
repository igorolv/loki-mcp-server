package ru.it_spectrum.ai.loki.mcp.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.*;

public record ListConnectionsResult(
        @Schema(requiredMode = REQUIRED) List<ConnectionSummary> connections) {
    public ListConnectionsResult {
        connections = List.copyOf(connections);
    }
}
