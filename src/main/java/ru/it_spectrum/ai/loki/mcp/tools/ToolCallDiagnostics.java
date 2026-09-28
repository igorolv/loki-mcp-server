package ru.it_spectrum.ai.loki.mcp.tools;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;
import ru.it_spectrum.ai.loki.mcp.error.ErrorCode;
import ru.it_spectrum.ai.loki.mcp.service.Diagnostics;
import ru.it_spectrum.ai.loki.mcp.error.Errors;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * One diagnostic line per tool call with the connection in the MDC. Errors pass on unchanged: Spring AI turns them
 * into the error result for the model; input schema errors are answered by the SDK before a tool is called.
 */
@Aspect
@Component
public class ToolCallDiagnostics {
    private static final Logger log = LoggerFactory.getLogger(ToolCallDiagnostics.class);

    @Around("@annotation(tool)")
    public Object call(ProceedingJoinPoint call, McpTool tool) throws Throwable {
        var signature = (MethodSignature) call.getSignature();
        String name = tool.name().isBlank() ? signature.getName() : tool.name();
        String connection = null;
        var shown = new HashMap<String, Object>();
        String[] names = signature.getParameterNames();
        Object[] values = call.getArgs();
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals("connection")) connection = values[i] instanceof String text ? text : null;
            else if (values[i] != null) shown.put(names[i], values[i]);
        }
        String previous = Diagnostics.enter(connection);
        long started = System.nanoTime();
        try {
            Object result = call.proceed();
            int bytes = result instanceof String text ? text.getBytes(StandardCharsets.UTF_8).length : 0;
            log.info("Tool {} {} -> ok, {} bytes, {} ms", name, Diagnostics.arguments(shown), bytes, Diagnostics.millisSince(started));
            return result;
        } catch (RuntimeException failure) {
            var error = Errors.from(failure);
            log.warn("Tool {} {} -> {}, {} ms: {}", name, Diagnostics.arguments(shown), error.code(),
                    Diagnostics.millisSince(started), Diagnostics.value(error.message()));
            if (error.code() == ErrorCode.INTERNAL_ERROR) log.error("Internal failure in tool {}", name, failure);
            throw failure;
        } finally {
            Diagnostics.leave(previous);
        }
    }
}
