package ru.it_spectrum.ai.loki.mcp.connection;

import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;
import ru.it_spectrum.ai.loki.mcp.service.Errors;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable startup snapshot. Resolving a connection never probes an endpoint.
 */
public final class ConnectionRegistry {
    private final Map<String, ConnectionDefinition> definitions;

    public ConnectionRegistry(List<ConnectionDefinition> entries) {
        var index = new LinkedHashMap<String, ConnectionDefinition>();
        for (var entry : entries) {
            if (index.putIfAbsent(entry.name(), entry) != null) throw Errors.configuration();
        }
        if (index.isEmpty()) throw Errors.configuration();
        definitions = Collections.unmodifiableMap(index);
    }

    public List<ConnectionDefinition> list() {
        return List.copyOf(definitions.values());
    }

    public ConnectionDefinition require(String name) {
        if (name == null || name.isBlank()) {
            throw Errors.failure(ErrorCode.CONNECTION_REQUIRED, "Specify connection explicitly; use listConnections to discover names.");
        }
        if (!ConnectionDefinition.validName(name)) {
            throw Errors.failure(ErrorCode.INVALID_CONNECTION, "Invalid connection name; use a name from listConnections.");
        }
        var connection = definitions.get(name);
        if (connection == null) {
            throw Errors.failure(ErrorCode.UNKNOWN_CONNECTION, "Unknown connection; use a name from listConnections.");
        }
        return connection;
    }
}
