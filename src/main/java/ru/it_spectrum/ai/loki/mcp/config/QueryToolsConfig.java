package ru.it_spectrum.ai.loki.mcp.config;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;
import ru.it_spectrum.ai.loki.mcp.model.ToolError;
import ru.it_spectrum.ai.loki.mcp.service.*;
import ru.it_spectrum.ai.loki.mcp.tools.ConnectionTools;
import ru.it_spectrum.ai.loki.mcp.tools.DiscoveryTools;
import ru.it_spectrum.ai.loki.mcp.tools.QueryTools;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;

/** Wraps every tool: explicit connection, argument type check, safe text errors and a last-resort size guard. */
@Configuration(proxyBeanMethods = false)
public class QueryToolsConfig {
    /** listConnections has no connection; its text is bounded by configuration size. */
    static final int CATALOG_BYTES = 65_536;

    @Bean
    public List<SyncToolSpecification> queryToolSpecifications(QueryService service, ConnectionsService connections,
                                                               DiscoveryService discovery, ConnectionRegistry registry) {
        var provider = new SyncMcpToolProvider(List.of(new QueryTools(service), new ConnectionTools(connections), new DiscoveryTools(discovery))) {
            @Override protected Class<? extends Throwable> doGetToolCallException() { return Error.class; }
        };
        var validator = new DefaultJsonSchemaValidator();
        return provider.getToolSpecifications().stream().map(spec -> SyncToolSpecification.builder().tool(spec.tool())
                .callHandler((exchange, request) -> {
                    try {
                        var args = request.arguments() == null ? Map.<String, Object>of() : request.arguments();
                        String name = spec.tool().name();
                        int maximum = CATALOG_BYTES;
                        if (!name.equals("listConnections")) {
                            if (!(args.get("connection") instanceof String connection) || connection.isBlank())
                                throw Errors.failure(ErrorCode.CONNECTION_REQUIRED, "Pass connection explicitly; use listConnections to see the names.");
                            maximum = registry.require(connection).limits().maxResponseBytes();
                        }
                        if (!validator.validate(spec.tool().inputSchema(), args).valid())
                            throw Errors.invalid("Argument types are wrong. Times, query and selector are strings; limit, before and after are integers; raw is a boolean.");
                        var result = spec.callHandler().apply(exchange, request);
                        return size(result) > maximum ? error(new ToolError(ErrorCode.RESPONSE_BUDGET_EXCEEDED,
                                "Response exceeds maxResponseBytes of this connection. Narrow the query or lower the limit.", false)) : result;
                    }
                    catch (Exception exception) {
                        var error = Errors.from(exception);
                        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                            if (cause instanceof LokiOperationException safe) { error = safe.error(); break; }
                        }
                        return error(error);
                    }
                }).build()).toList();
    }

    static CallToolResult error(ToolError error) {
        return CallToolResult.builder().isError(true).addTextContent(error.text()).build();
    }

    static int size(CallToolResult result) {
        int total = 0;
        if (result.content() != null) for (var content : result.content())
            if (content instanceof TextContent text && text.text() != null) total += text.text().getBytes(StandardCharsets.UTF_8).length;
        return total;
    }
}
