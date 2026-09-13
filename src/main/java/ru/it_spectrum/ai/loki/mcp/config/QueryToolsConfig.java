package ru.it_spectrum.ai.loki.mcp.config;

import java.util.List;
import java.util.Map;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.it_spectrum.ai.loki.mcp.service.*;
import ru.it_spectrum.ai.loki.mcp.tools.QueryTools;
import ru.it_spectrum.ai.loki.mcp.tools.ConnectionTools;
import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class QueryToolsConfig {
    @Bean
    public List<SyncToolSpecification> queryToolSpecifications(QueryService service, ConnectionsService connections) {
        var provider = new SyncMcpToolProvider(List.of(new QueryTools(service), new ConnectionTools(connections))) {
            @Override protected Class<? extends Throwable> doGetToolCallException() { return Error.class; }
        };
        var mapper = new JsonMapper();
        var validator = new DefaultJsonSchemaValidator();
        return provider.getToolSpecifications().stream().map(spec -> SyncToolSpecification.builder().tool(spec.tool())
                .callHandler((exchange, request) -> {
                    try {
                        var args = request.arguments() == null ? Map.<String, Object>of() : request.arguments();
                        if (!spec.tool().name().equals("listConnections") && (args.get("connection") == null
                                || args.get("connection") instanceof String name && name.isBlank())) {
                            throw Errors.failure(ErrorCode.CONNECTION_REQUIRED, "Specify connection explicitly; use listConnections to discover names.");
                        }
                        if (!validator.validate(spec.tool().inputSchema(), args).valid()) throw QueryTime.invalid();
                        return spec.callHandler().apply(exchange, request);
                    }
                    catch (Exception exception) {
                        var error = Errors.from(exception);
                        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                            if (cause instanceof LokiOperationException safe) { error = safe.error(); break; }
                        }
                        @SuppressWarnings("unchecked") Map<String, Object> payload = mapper.convertValue(error, Map.class);
                        return CallToolResult.builder().isError(true).structuredContent(payload)
                                .addTextContent(mapper.writeValueAsString(error)).build();
                    }
                }).build()).toList();
    }
}
