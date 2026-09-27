package ru.it_spectrum.ai.loki.mcp.service;

import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionLimits;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;

@Service
public class ConnectionsService {
    private final ConnectionRegistry registry;

    public ConnectionsService(ConnectionRegistry registry) {
        this.registry = registry;
    }

    /**
     * Connection names, operator hints and the limits needed to plan tool calls.
     */
    public String list() {
        var text = new StringBuilder();
        for (var connection : registry.list()) {
            text.append(connection.name());
            if (connection.description() != null) text.append(" — ").append(connection.description());
            if (connection.hint() != null) text.append(". ").append(connection.hint());
            if (text.charAt(text.length() - 1) != '.') text.append('.');
            text.append('\n').append("  Limits: ");
            appendLimits(text, connection.limits());
            text.append('\n');
        }
        return text.toString().stripTrailing();
    }

    private void appendLimits(StringBuilder text, ConnectionLimits limits) {
        text.append("discoverLogs ").append(duration(limits.maxDiscoveryIntervalSeconds()));
        text.append("; queryLogs ").append(duration(limits.maxIntervalSeconds()));
        text.append(", max ").append(limits.maxEntries()).append(" lines");
        text.append("; countLogs ").append(duration(limits.maxCountIntervalSeconds()));
        text.append(" (total/label/label+time), ").append(duration(limits.maxTimeCountIntervalSeconds()));
        text.append(" (time-only)");
        text.append("; exportLogs ").append(duration(limits.maxIntervalSeconds()));
        text.append("; request timeout ").append(timeout(limits.requestTimeoutMs())).append('.');
    }

    private String duration(long seconds) {
        if (seconds % 86_400 == 0) return seconds / 86_400 + "d";
        if (seconds % 3_600 == 0) return seconds / 3_600 + "h";
        if (seconds % 60 == 0) return seconds / 60 + "m";
        return seconds + "s";
    }

    private String timeout(int milliseconds) {
        if (milliseconds % 1_000 == 0) return milliseconds / 1_000 + "s";
        return milliseconds + "ms";
    }
}
