package ru.it_spectrum.ai.loki.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.util.Map;
import java.util.List;
import java.util.stream.StreamSupport;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class StdioSmokeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(60)
    void executableJarSpeaksOnlyJsonRpcOnStdout(boolean overrideFile) throws Exception {
        var upstream = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            try (exchange) {
                String query = java.net.URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
                int status = query.contains("query=fail") ? 403
                        : exchange.getRequestURI().getPath().endsWith("/series") && query.contains("blocked") ? 404 : 200;
                String body = status == 403 ? "SECRET_TOKEN upstream error" : query.contains("step=")
                        ? "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"metric\":{\"kind\":\"test\"},\"values\":[[1700000000.125,\"NaN\"]]}],\"stats\":{\"summary\":{\"totalLinesProcessed\":200}}}}"
                        : query.contains("query=metric")
                        ? "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{\"kind\":\"test\"},\"value\":[1700000000.125,\"NaN\"]}]}}"
                        : "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[{\"stream\":{\"kind\":\"test\"},\"values\":[[\"1700000000123456789\",\"Ошибка 🐈\"]]}]}}";
                if (exchange.getRequestURI().getPath().endsWith("/series")) body = status == 404 ? "SECRET_TOKEN path blocked"
                        : "{\"status\":\"success\",\"data\":[{\"kind\":\"test\"}]}";
                else if (query.contains("largeMetric")) body = mapper.writeValueAsString(Map.of("status", "success", "data",
                        Map.of("resultType", "vector", "result", java.util.stream.IntStream.range(0, 100).mapToObj(i ->
                                Map.of("metric", Map.of("kind", "test" + i), "value", List.of(1700000000.125, "NaN"))).toList())));
                else if (query.contains("large")) body = mapper.writeValueAsString(Map.of("status", "success", "data",
                        Map.of("resultType", "streams", "result", List.of(Map.of("stream", Map.of("kind", "test"),
                                "values", java.util.stream.IntStream.range(0, 12).mapToObj(i -> List.of("1700000000123456789",
                                        mapper.writeValueAsString(Map.of("message", "Ошибка 🐈\"\\\n".repeat(1000))))).toList())))));
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        upstream.start();
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Path stderr = temporaryDirectory.resolve("stderr.log");
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "-jar",
                System.getProperty("mcp.test.jar"));
        builder.directory(temporaryDirectory.toFile());
        Files.createDirectories(temporaryDirectory.resolve("data"));
        Files.writeString(temporaryDirectory.resolve("data/connections.json"), """
                {"connections":{
                  "dev":{"description":"Development","url":"http://127.0.0.1:1/private",
                    "auth":{"type":"BEARER","token":"SECRET_TOKEN"},"tenant":"SECRET_TENANT"},
                  "test":{"url":"http://127.0.0.1:%d","limits":{"maxResponseBytes":6000}},
                  "tiny":{"url":"http://127.0.0.1:%d","limits":{"maxResponseBytes":1024}}
                }}
                """.formatted(upstream.getAddress().getPort(), upstream.getAddress().getPort()));
        builder.environment().remove("LOKI_MCP_CONNECTIONS_FILE");
        if (overrideFile) {
            Path explicitFile = temporaryDirectory.resolve("custom.json");
            Files.move(temporaryDirectory.resolve("data/connections.json"), explicitFile);
            Files.writeString(temporaryDirectory.resolve("data/connections.json"), "invalid default file");
            builder.environment().put("LOKI_MCP_CONNECTIONS_FILE", explicitFile.toString());
        }
        builder.environment().put("LOKI_MCP_DATA_DIR", temporaryDirectory.resolve("data").toString());
        builder.redirectError(stderr.toFile());
        Process process = builder.start();
        var stdout = new LinkedBlockingQueue<String>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var lines = process.inputReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = lines.readLine()) != null) {
                    stdout.add(line);
                }
            } catch (Exception e) {
                stdout.add("STDOUT_READ_FAILED: " + e);
            }
        });
        try (var input = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8))) {
            send(input, """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"stdio-smoke","version":"1.0"}}}
                    """);
            JsonNode initialized = response(stdout, stderr);
            assertEquals(1, initialized.path("id").asInt());
            assertEquals("loki-mcp-server", initialized.path("result").path("serverInfo").path("name").asText());
            assertFalse(initialized.path("result").path("protocolVersion").asText().isBlank());
            send(input, """
                    {"jsonrpc":"2.0","method":"notifications/initialized"}
                    """);

            // Multiple outstanding requests exercise the real transport without fake tools.
            for (int id = 2; id <= 17; id++) {
                send(input, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"ping\"}");
            }
            var ids = new HashSet<Integer>();
            for (int i = 0; i < 16; i++) {
                JsonNode pong = response(stdout, stderr);
                int id = pong.path("id").asInt();
                assertTrue(id >= 2 && id <= 17);
                assertTrue(ids.add(id), "Duplicate response id");
                assertTrue(pong.path("result").isObject());
            }
            assertTrue(process.isAlive(), "Server should remain available after initialization");
            send(input, """
                    {"jsonrpc":"2.0","id":18,"method":"tools/list"}
                    """);
            JsonNode catalog = response(stdout, stderr).path("result").path("tools");
            assertEquals(5, catalog.size());
            JsonNode tool = StreamSupport.stream(catalog.spliterator(), false)
                    .filter(t -> t.path("name").asText().equals("listConnections")).findFirst().orElseThrow();
            assertEquals("listConnections", tool.path("name").asText());
            assertTrue(tool.path("annotations").path("readOnlyHint").asBoolean());
            assertFalse(tool.path("annotations").path("destructiveHint").asBoolean());
            assertTrue(tool.path("annotations").path("idempotentHint").asBoolean());
            assertFalse(tool.path("annotations").path("openWorldHint").asBoolean());
            assertTrue(tool.path("outputSchema").isObject());
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = mapper.convertValue(tool.path("outputSchema"), Map.class);
            var validator = new DefaultJsonSchemaValidator();
            for (int id = 19; id <= 34; id++) {
                send(input, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"method\":\"tools/call\",\"params\":{\"name\":\"listConnections\",\"arguments\":{}}}");
            }
            ids.clear();
            for (int i = 0; i < 16; i++) {
                JsonNode call = response(stdout, stderr);
                int id = call.path("id").asInt();
                assertTrue(id >= 19 && id <= 34 && ids.add(id));
                JsonNode result = call.path("result");
                assertFalse(result.path("isError").asBoolean(), result.toString());
                JsonNode payload = result.path("structuredContent");
                assertTrue(payload.isObject(), result.toString());
                var validation = validator.validate(schema, mapper.convertValue(payload, Object.class));
                assertTrue(validation.valid(), validation.errorMessage());
                assertEquals(3, payload.path("connections").size());
                assertEquals("dev", payload.path("connections").get(0).path("name").asText());
                assertEquals("Development", payload.path("connections").get(0).path("description").asText());
                assertEquals("test", payload.path("connections").get(1).path("name").asText());
                assertFalse(payload.path("connections").get(1).has("description"));
                // Validate the actual text representation too when the SDK duplicates structured content.
                for (JsonNode content : result.path("content")) {
                    if ("text".equals(content.path("type").asText())) {
                        assertEquals(payload, mapper.readTree(content.path("text").asText()));
                    }
                }
                assertNoSecrets(call.toString());
            }
            var schemas = new HashMap<String, Map<String, Object>>();
            for (var declaration : catalog) {
                String name = declaration.path("name").asText();
                if (!name.equals("listConnections")) {
                    assertTrue(declaration.path("annotations").path("openWorldHint").asBoolean());
                    assertTrue(declaration.path("annotations").path("readOnlyHint").asBoolean());
                    @SuppressWarnings("unchecked") Map<String, Object> output = mapper.convertValue(declaration.path("outputSchema"), Map.class);
                    schemas.put(name, output);
                }
            }
            // Oversized payloads and minimum budget traverse the real outbound transport.
            for (int i = 0; i < 16; i++) {
                String name = i % 4 == 2 ? "queryMetrics" : i % 4 == 3 ? "discoverLogs" : "queryLogs";
                String connection = i % 4 == 0 ? "tiny" : "test";
                var args = new HashMap<String, Object>();
                args.put("connection", connection);
                if (name.equals("queryMetrics")) {
                    args.put("query", "largeMetric"); args.put("mode", "instant"); args.put("time", "1700000001000000000");
                } else {
                    args.put(name.equals("discoverLogs") ? "selector" : "query", name.equals("discoverLogs") ? "{kind=\"large\"}" : "large");
                    args.put("start", "1700000000000000000"); args.put("end", "1700000001000000000");
                    if (name.equals("queryLogs")) args.put("fields", List.of("normalized"));
                }
                send(input, mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", "budget🐈\"\\" + i,
                        "method", "tools/call", "params", Map.of("name", name, "arguments", args))));
            }
            for (int i = 0; i < 16; i++) {
                String wire = stdout.poll(20, TimeUnit.SECONDS);
                assertNotNull(wire);
                var call = mapper.readTree(wire);
                String id = call.path("id").asText();
                int index = Integer.parseInt(id.substring("budget🐈\"\\".length()));
                assertTrue(wire.getBytes(StandardCharsets.UTF_8).length + 1 <= (index % 4 == 0 ? 1024 : 6000));
                var result = call.path("result");
                var payload = result.path("structuredContent");
                assertEquals(payload, mapper.readTree(result.path("content").get(0).path("text").asText()));
                if (index % 4 == 0) {
                    assertTrue(result.path("isError").asBoolean());
                    assertEquals("RESPONSE_BUDGET_EXCEEDED", payload.path("code").asText());
                } else {
                    assertFalse(result.path("isError").asBoolean(), result.toString());
                    String name = index % 4 == 2 ? "queryMetrics" : index % 4 == 3 ? "discoverLogs" : "queryLogs";
                    var validation = validator.validate(schemas.get(name), mapper.convertValue(payload, Object.class));
                    assertTrue(validation.valid(), validation.errorMessage());
                    assertTrue(payload.path("limitations").toString().contains("RESPONSE_BYTE_BUDGET"));
                }
            }
            // Outstanding data calls exercise actual handlers and SDK serialization, including optional fields.
            send(input, mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 110, "method", "tools/call",
                    "params", Map.of("name", "queryLogs", "arguments", Map.of("connection", "test", "query", "large",
                            "start", "1700000000000000000", "end", "1700000001000000000", "limit", 1, "fields", List.of())))));
            var firstPage = response(stdout, stderr).path("result").path("structuredContent");
            String cursor = firstPage.path("nextCursor").asText();
            assertFalse(cursor.isBlank());
            for (int id = 111; id < 127; id++) {
                send(input, mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "method", "tools/call",
                        "params", Map.of("name", "continueLogs", "arguments", Map.of("connection", "test", "cursor", cursor)))));
            }
            ids.clear();
            for (int i = 0; i < 16; i++) {
                String wire = stdout.poll(20, TimeUnit.SECONDS);
                assertNotNull(wire);
                assertTrue(wire.getBytes(StandardCharsets.UTF_8).length + 1 <= 6000);
                assertFalse(wire.contains("loki.internal"));
                var call = mapper.readTree(wire);
                assertTrue(ids.add(call.path("id").asInt()));
                var result = call.path("result");
                assertFalse(result.path("isError").asBoolean(), result.toString());
                var payload = result.path("structuredContent");
                assertEquals(1, payload.path("returnedEntries").asInt());
                assertTrue(payload.has("nextCursor"));
                assertEquals(payload, mapper.readTree(result.path("content").get(0).path("text").asText()));
                var validation = validator.validate(schemas.get("continueLogs"), mapper.convertValue(payload, Object.class));
                assertTrue(validation.valid(), validation.errorMessage());
            }
            send(input, mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 127, "method", "tools/call",
                    "params", Map.of("name", "continueLogs", "arguments", Map.of("connection", "dev", "cursor", cursor)))));
            assertEquals("INVALID_CURSOR", response(stdout, stderr).path("result").path("structuredContent").path("code").asText());
            for (int id = 35; id < 51; id++) {
                String name = id % 2 == 0 ? "queryLogs" : "queryMetrics";
                Map<String, Object> arguments = id % 2 == 0
                        ? Map.of("connection", "test", "query", "logs", "start", "1700000000000000000", "end", "1700000001000000000")
                        : id % 4 == 1 ? Map.of("connection", "test", "query", "metric", "mode", "range",
                                "start", "1700000000000000000", "end", "1700000001000000000", "stepSeconds", 0.125)
                        : Map.of("connection", "test", "query", "metric", "mode", "instant", "time", "1700000001000000000");
                send(input, mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "method", "tools/call",
                        "params", Map.of("name", name, "arguments", arguments))));
            }
            ids.clear();
            for (int i = 0; i < 16; i++) {
                var call = response(stdout, stderr);
                int id = call.path("id").asInt();
                assertTrue(id >= 35 && id < 51 && ids.add(id));
                var result = call.path("result");
                assertFalse(result.path("isError").asBoolean(), result.toString());
                var payload = result.path("structuredContent");
                var validation = validator.validate(schemas.get(id % 2 == 0 ? "queryLogs" : "queryMetrics"), mapper.convertValue(payload, Object.class));
                assertTrue(validation.valid(), validation.errorMessage());
                assertEquals(payload, mapper.readTree(result.path("content").get(0).path("text").asText()));
                if (id % 4 == 1) {
                    assertEquals(200, payload.path("totalLinesProcessed").asInt());
                    assertEquals(0.125, payload.path("stepSeconds").asDouble());
                } else assertFalse(payload.has("totalLinesProcessed"));
                if (id % 2 == 0) assertEquals("1700000000123456789", payload.path("events").get(0).path("timestampNanos").asText());
                else {
                    if (id % 4 == 3) assertFalse(payload.has("stepSeconds"));
                    assertEquals("NaN", payload.path("series").get(0).path("samples").get(0).path("value").asText());
                }
                assertNoSecrets(call.toString());
            }
            for (int id = 70; id < 86; id++) {
                send(input, mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "method", "tools/call",
                        "params", Map.of("name", "discoverLogs", "arguments", Map.of("connection", "test",
                                "selector", id % 2 == 0 ? "{kind=\"test\"}" : "{kind=\"blocked\"}",
                                "start", "1700000000000000000", "end", "1700000001000000000")))));
            }
            ids.clear();
            for (int i = 0; i < 16; i++) {
                var call = response(stdout, stderr);
                int id = call.path("id").asInt();
                assertTrue(id >= 70 && id < 86 && ids.add(id));
                var result = call.path("result");
                assertFalse(result.path("isError").asBoolean(), result.toString());
                var payload = result.path("structuredContent");
                var validation = validator.validate(schemas.get("discoverLogs"), mapper.convertValue(payload, Object.class));
                assertTrue(validation.valid(), validation.errorMessage());
                assertEquals(payload, mapper.readTree(result.path("content").get(0).path("text").asText()));
                assertEquals(1, payload.path("coverage").path("entriesExamined").asInt());
                var capability = payload.path("capabilities").get(0);
                assertEquals(id % 2 == 0 ? "AVAILABLE" : "UNAVAILABLE_AT_PATH", capability.path("availability").asText());
                assertEquals(id % 2 != 0, capability.has("errorCode"));
                assertNoSecrets(call.toString());
            }
            for (var bad : List.of(Map.of("selector", "{kind=\"test\"}"),
                    Map.of("connection", "test", "selector", "{kind=\"test\"}", "sampleLimit", "SECRET_TOKEN"))) {
                var args = new HashMap<String, Object>(bad);
                args.put("start", "now-1s"); args.put("end", "now");
                send(input, mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 86, "method", "tools/call",
                        "params", Map.of("name", "discoverLogs", "arguments", args))));
                var result = response(stdout, stderr).path("result");
                assertTrue(result.path("isError").asBoolean());
                assertEquals(args.containsKey("connection") ? "INVALID_ARGUMENT" : "CONNECTION_REQUIRED",
                        result.path("structuredContent").path("code").asText());
                assertNoSecrets(result.toString());
            }
            String[] errorCodes = {"CONNECTION_REQUIRED", "UNKNOWN_CONNECTION", "UPSTREAM_FORBIDDEN", "INVALID_ARGUMENT", "INVALID_ARGUMENT"};
            for (int index = 0; index < errorCodes.length; index++) {
                var arguments = new HashMap<String, Object>(Map.of("query", index == 2 ? "fail" : "logs",
                        "start", "now-1s", "end", "now"));
                if (index != 0) arguments.put("connection", index == 1 ? "missing" : "test");
                if (index == 3) arguments.put("limit", 0);
                if (index == 4) arguments.put("limit", "SECRET_TOKEN");
                send(input, mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 51 + index, "method", "tools/call",
                        "params", Map.of("name", "queryLogs", "arguments", arguments))));
                var call = response(stdout, stderr);
                var result = call.path("result");
                assertTrue(result.path("isError").asBoolean(), result.toString());
                var payload = result.path("structuredContent");
                assertTrue(payload.has("code"), result.toString());
                assertEquals(errorCodes[index], payload.path("code").asText(), result.toString());
                assertEquals(payload, mapper.readTree(result.path("content").get(0).path("text").asText()));
                assertNoSecrets(call.toString());
            }
        } finally {
            upstream.stop(0);
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
            reader.join(5_000);
        }
        assertFalse(reader.isAlive(), "Stdout reader did not stop");
        for (String remaining : stdout) {
            assertEquals("2.0", mapper.readTree(remaining).path("jsonrpc").asText());
        }
        assertTrue(Files.size(stderr) > 0, "Startup diagnostics should go to stderr");
        assertTrue(Files.size(temporaryDirectory.resolve("data/logs/loki-mcp-server.log")) > 0,
                "Rolling file logging should be active");
        assertFalse(Files.readString(stderr).contains("Tomcat"), "No embedded web server expected");
        assertNoSecrets(Files.readString(stderr));
        assertNoSecrets(Files.readString(temporaryDirectory.resolve("data/logs/loki-mcp-server.log")));
        assertFalse(Files.readString(stderr).contains("Ошибка 🐈"));
        assertFalse(Files.readString(temporaryDirectory.resolve("data/logs/loki-mcp-server.log")).contains("Ошибка 🐈"));
    }

    private static void assertNoSecrets(String text) {
        for (String secret : new String[]{"SECRET_TOKEN", "SECRET_TENANT", "127.0.0.1", "/private"}) {
            assertFalse(text.contains(secret), "Transport configuration leaked");
        }
    }

    @Test
    @Timeout(30)
    void invalidConfigurationFailsStartupWithoutLeakingSecretsToDiagnostics() throws Exception {
        Path config = temporaryDirectory.resolve("invalid.json");
        Files.writeString(config, """
                {"connections":{"dev":{"url":"http://127.0.0.1/private",
                "auth":{"type":"BEARER","token":"SECRET_TOKEN","unexpected":"SECRET_TENANT"}}}}
                """);
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-jar", System.getProperty("mcp.test.jar"));
        builder.directory(temporaryDirectory.toFile());
        builder.environment().put("LOKI_MCP_DATA_DIR", temporaryDirectory.resolve("data").toString());
        builder.environment().put("LOKI_MCP_CONNECTIONS_FILE", config.toString());
        Path stdout = temporaryDirectory.resolve("stdout.log");
        Path stderr = temporaryDirectory.resolve("stderr.log");
        builder.redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Invalid configuration should fail startup");
            assertNotEquals(0, process.exitValue());
            assertEquals("", Files.readString(stdout));
            String diagnostics = Files.readString(stderr);
            assertTrue(diagnostics.contains("Cannot load connections"));
            assertNoSecrets(diagnostics);
            assertNoSecrets(Files.readString(temporaryDirectory.resolve("data/logs/loki-mcp-server.log")));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private JsonNode response(LinkedBlockingQueue<String> stdout, Path stderr) throws Exception {
        String line = stdout.poll(20, TimeUnit.SECONDS);
        assertNotNull(line, () -> "No JSON-RPC response. See " + stderr);
        JsonNode node = mapper.readTree(line); // Any banner or diagnostic on stdout fails the test.
        assertEquals("2.0", node.path("jsonrpc").asText());
        assertFalse(node.has("error"), node.toString());
        return node;
    }

    private static void send(BufferedWriter input, String json) throws Exception {
        input.write(json.strip());
        input.newLine();
        input.flush();
    }
}
