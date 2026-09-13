package ru.it_spectrum.ai.loki.mcp.config;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.*;
import reactor.core.publisher.Mono;
import ru.it_spectrum.ai.loki.mcp.service.ResponseBudget;

/** Keeps the SDK's stdio reader/writer and scheduling; only final tool responses are projected. */
public final class BudgetedStdioTransport extends StdioServerTransportProvider {
    private final ResponseBudget budget;
    public BudgetedStdioTransport(McpJsonMapper mapper, ResponseBudget budget) {
        super(mapper); this.budget = budget;
    }

    @Override public void setSessionFactory(McpServerSession.Factory factory) {
        super.setSessionFactory(transport -> factory.create(new McpServerTransport() {
            @Override public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
                return Mono.defer(() -> transport.sendMessage(message instanceof McpSchema.JSONRPCResponse response
                        ? budget.fit(response) : message));
            }
            @Override public <T> T unmarshalFrom(Object data, TypeRef<T> type) { return transport.unmarshalFrom(data, type); }
            @Override public Mono<Void> closeGracefully() { return transport.closeGracefully(); }
            @Override public void close() { transport.close(); }
        }));
    }
}
