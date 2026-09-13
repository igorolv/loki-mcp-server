package ru.it_spectrum.ai.loki.mcp.service;

import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.model.ListConnectionsResult;

@Service
public class ConnectionsService {
    private final ConnectionRegistry registry;
    public ConnectionsService(ConnectionRegistry registry) { this.registry = registry; }
    public ListConnectionsResult list() { return new ListConnectionsResult(registry.list()); }
}
