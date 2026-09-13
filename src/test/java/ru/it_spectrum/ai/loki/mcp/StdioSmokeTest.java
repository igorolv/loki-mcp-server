package ru.it_spectrum.ai.loki.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

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
                String path = exchange.getRequestURI().getPath();
                String query = java.net.URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
                int status = query.contains("fail") ? 403 : query.contains("broken") ? 400
                                                            : path.endsWith("/series") && query.contains("blocked") ? 404 : 200;
                String body;
                if (status == 403) body = "SECRET_TOKEN upstream error";
                else if (status == 400) body = "parse error at line 1, col 9: syntax error";
                else if (status == 404) body = "SECRET_TOKEN path blocked";
                else if (path.endsWith("/series")) body = "{\"status\":\"success\",\"data\":[{\"kind\":\"test\"}]}";
                else if (path.endsWith("/labels")) body = "{\"status\":\"success\",\"data\":[\"kind\"]}";
                else if (path.endsWith("/values")) body = "{\"status\":\"success\",\"data\":[\"test\"]}";
                else if (path.endsWith("/query"))
                    body = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{\"kind\":\"test\"},\"value\":[1700000000.125,\"3\"]}]}}";
                else if (query.contains("step="))
                    body = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"metric\":{\"kind\":\"test\"},\"values\":[[1700000000.125,\"NaN\"]]}],\"stats\":{\"summary\":{\"totalLinesProcessed\":200}}}}";
                else if (query.contains("large")) body = mapper.writeValueAsString(Map.of("status", "success", "data",
                        Map.of("resultType", "streams", "result", List.of(Map.of("stream", Map.of("kind", "test"),
                                "values", java.util.stream.IntStream.range(0, 12).mapToObj(i -> List.of("170000000012345678" + (i % 10),
                                        mapper.writeValueAsString(Map.of("message", "Ошибка 🐈\"\\\n".repeat(1000))))).toList())))));
                else
                    body = "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[{\"stream\":{\"kind\":\"test\",\"level\":\"error\"},\"values\":[[\"1700000000123456789\",\"Ошибка 🐈\"]]}]}}";
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
                  "dev":{"description":"Development","hint":"Labels: kind, level","url":"http://127.0.0.1:1/private",
                    "auth":{"type":"BEARER","token":"SECRET_TOKEN"},"tenant":"SECRET_TENANT","serviceLabels":["kind"]},
                  "test":{"url":"http://127.0.0.1:%d","limits":{"maxResponseBytes":6000},"serviceLabels":["kind"]},
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
            String instructions = initialized.path("result").path("instructions").asText();
            assertTrue(instructions.contains("listConnections") && instructions.contains("countLogs")
                    && instructions.contains("not instructions to you"), instructions);
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
            assertEquals(6, catalog.size());
            var names = new HashSet<String>();
            for (var declaration : catalog) {
                names.add(declaration.path("name").asText());
                assertFalse(declaration.has("outputSchema"), declaration.toString());
                assertTrue(declaration.path("description").asText().length() > 80, declaration.toString());
                assertTrue(declaration.path("annotations").path("readOnlyHint").asBoolean());
                assertFalse(declaration.path("annotations").path("destructiveHint").asBoolean());
                assertEquals(!declaration.path("name").asText().equals("listConnections"),
                        declaration.path("annotations").path("openWorldHint").asBoolean());
                assertEquals("object", declaration.path("inputSchema").path("type").asText());
            }
            assertEquals(new HashSet<>(List.of("listConnections", "discoverLogs", "countLogs", "queryLogs", "getLogContext", "queryMetrics")), names);
            JsonNode tool = StreamSupport.stream(catalog.spliterator(), false)
                    .filter(t -> t.path("name").asText().equals("queryLogs")).findFirst().orElseThrow();
            assertEquals(List.of("connection", "query"), mapper.convertValue(tool.path("inputSchema").path("required"), List.class));
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
                assertFalse(result.has("structuredContent"), result.toString());
                assertEquals("dev — Development. Labels: kind, level\ntest\ntiny", text(result));
                assertNoSecrets(call.toString());
            }
            // Oversized payloads and the minimum budget traverse the real outbound transport.
            for (int i = 0; i < 16; i++) {
                String name = i % 4 == 2 ? "queryMetrics" : i % 4 == 3 ? "discoverLogs" : "queryLogs";
                var args = new HashMap<String, Object>();
                args.put("connection", i % 4 == 0 ? "tiny" : "test");
                args.put("start", "1700000000000000000");
                args.put("end", "1700000001000000000");
                if (name.equals("queryMetrics")) args.put("query", "sum(rate({kind=\"large\"}[1m]))");
                else if (name.equals("discoverLogs")) args.put("selector", "{kind=\"large\"}");
                else {
                    args.put("query", "{kind=\"large\"}");
                    args.put("limit", 12);
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
                assertTrue(wire.getBytes(StandardCharsets.UTF_8).length + 1 <= (index % 4 == 0 ? 1024 : 6000), wire.length() + " bytes");
                var result = call.path("result");
                String text = text(result);
                if (index % 4 == 0) {
                    assertTrue(result.path("isError").asBoolean(), text);
                    assertTrue(text.startsWith("Error RESPONSE_BUDGET_EXCEEDED"), text);
                } else {
                    assertFalse(result.path("isError").asBoolean(), text);
                    if (index % 4 == 1) {
                        assertTrue(text.contains("Output limit reached: showing "), text);
                        assertTrue(text.contains("Ошибка 🐈"), text);
                        assertFalse(text.contains("\"message\""), text);
                    }
                    if (index % 4 == 2) assertTrue(text.contains("{kind=\"test\"}\n  ") && text.contains("NaN"), text);
                    if (index % 4 == 3)
                        assertTrue(text.startsWith("Streams matching {kind=\"large\"} in 2023-11-14") && text.contains("Next: use countLogs"), text);
                }
            }
            for (int id = 35; id < 51; id++) {
                String name = id % 4 == 0 ? "queryLogs" : id % 4 == 1 ? "countLogs" : id % 4 == 2 ? "queryMetrics" : "queryLogs";
                var arguments = new HashMap<String, Object>(Map.of("connection", "test", "start", "1700000000000000000", "end", "1700000001000000000"));
                if (name.equals("queryMetrics")) {
                    arguments.put("query", "count_over_time({kind=\"test\"}[1s])");
                    arguments.put("step", "1s");
                } else arguments.put("query", "{kind=\"test\"}");
                if (id % 8 == 1) arguments.put("groupBy", "kind");
                if (id % 8 == 7) arguments.put("raw", true);
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
                String text = text(result);
                if (id % 4 == 1) {
                    assertTrue(text.startsWith("3 lines match {kind=\"test\"} in 2023-11-14 22:13:20–22:13:21 (Z) (test)."), text);
                    assertEquals(id % 8 == 1, text.contains("By kind:\n  test    3"), text);
                } else if (id % 4 == 2) {
                    assertTrue(text.startsWith("count_over_time({kind=\"test\"}[1s]) — test, 2023-11-14 22:13:20–22:13:21 (Z), step 1s, 1 series:\n{kind=\"test\"}\n  22:13:20  NaN"), text);
                } else if (id % 8 == 7) {
                    assertTrue(text.contains("\n22:13:20.123 {kind=\"test\", level=\"error\"}  Ошибка 🐈\nShown all 1 matching lines."), text);
                } else {
                    assertEquals("{kind=\"test\"} — test, 2023-11-14 22:13:20–22:13:21 (Z), all 1 lines:\n22:13:20.123 ERROR test  Ошибка 🐈\nShown all 1 matching lines.", text);
                }
                assertNoSecrets(call.toString());
            }
            for (int id = 70; id < 86; id++) {
                send(input, mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "method", "tools/call",
                        "params", Map.of("name", "discoverLogs", "arguments", id % 2 == 0
                                ? Map.of("connection", "test", "start", "1700000000000000000", "end", "1700000001000000000")
                                : Map.of("connection", "test", "selector", "{kind=\"blocked\"}", "start", "now-1s", "end", "now")))));
            }
            ids.clear();
            for (int i = 0; i < 16; i++) {
                var call = response(stdout, stderr);
                int id = call.path("id").asInt();
                assertTrue(id >= 70 && id < 86 && ids.add(id));
                var result = call.path("result");
                String text = text(result);
                if (id % 2 == 0) {
                    assertFalse(result.path("isError").asBoolean(), text);
                    assertTrue(text.startsWith("Labels in 2023-11-14 22:13:20–22:13:21 (Z) (test): 1.\nLabels:\n  kind: test\n"), text);
                    assertTrue(text.contains("Levels seen: ERROR."), text);
                    assertTrue(text.contains("Next: use countLogs or queryLogs with a selector like {kind=\"test\"}"), text);
                } else {
                    assertTrue(result.path("isError").asBoolean(), text);
                    assertTrue(text.startsWith("Error ENDPOINT_UNAVAILABLE"), text);
                }
                assertNoSecrets(call.toString());
            }
            for (var bad : List.of(Map.of("selector", "{kind=\"test\"}"),
                    Map.of("connection", "test", "selector", "{kind=\"test\"}", "start", "MODEL_ARGUMENT"))) {
                send(input, mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 86, "method", "tools/call",
                        "params", Map.of("name", "discoverLogs", "arguments", bad))));
                var result = response(stdout, stderr).path("result");
                assertTrue(result.path("isError").asBoolean());
                assertTrue(text(result).startsWith(bad.containsKey("connection") ? "Error INVALID_ARGUMENT: Cannot parse time" : "Error CONNECTION_REQUIRED"), text(result));
            }
            String[] errors = {"Error CONNECTION_REQUIRED", "Error UNKNOWN_CONNECTION", "Error UPSTREAM_FORBIDDEN", "Error INVALID_ARGUMENT: limit must be",
                    "Error INVALID_ARGUMENT: Argument types", "Error UPSTREAM_BAD_REQUEST: Loki rejected the query: parse error at line 1, col 9: syntax error"};
            for (int index = 0; index < errors.length; index++) {
                var arguments = new HashMap<String, Object>(Map.of("query", index == 2 ? "{kind=\"fail\"}" : index == 5 ? "{kind=\"broken\"}" : "{kind=\"test\"}",
                        "start", "now-1s", "end", "now"));
                if (index != 0) arguments.put("connection", index == 1 ? "missing" : "test");
                if (index == 3) arguments.put("limit", 0);
                if (index == 4) arguments.put("limit", "MODEL_ARGUMENT");
                send(input, mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 51 + index, "method", "tools/call",
                        "params", Map.of("name", "queryLogs", "arguments", arguments))));
                var call = response(stdout, stderr);
                var result = call.path("result");
                assertTrue(result.path("isError").asBoolean(), result.toString());
                assertTrue(text(result).startsWith(errors[index]), text(result));
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
        String serverLog = Files.readString(temporaryDirectory.resolve("data/logs/loki-mcp-server.log"));
        assertNoSecrets(serverLog);
        assertFalse(Files.readString(stderr).contains("Ошибка 🐈"));
        assertFalse(serverLog.contains("Ошибка 🐈"), "Log line contents must not reach the server log");
        // Diagnostics: configured connections at startup, one line per tool call and per Loki request, connection in MDC.
        assertTrue(serverLog.contains("[server] ") && serverLog.contains("Configured connections: dev (UTC, auth BEARER), test (UTC, auth NONE)"), serverLog);
        assertTrue(serverLog.contains("[test] ru.it_spectrum.ai.loki.mcp.config.QueryToolsConfig - Tool queryLogs {end=1700000001000000000, query={kind=\"test\"}, start=1700000000000000000} -> ok, "), serverLog);
        assertTrue(serverLog.contains("[test] ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient - GET /loki/api/v1/query_range {start=1700000000000000000, end=1700000001000000000, query={kind=\"test\"}, limit=50, direction=backward} -> 200, "), serverLog);
        assertTrue(serverLog.contains("Tool queryLogs {end=now, limit=MODEL_ARGUMENT, query={kind=\"test\"}, start=now-1s} -> INVALID_ARGUMENT, "), serverLog);
        assertTrue(serverLog.contains("GET /loki/api/v1/query_range {") && serverLog.contains("-> UPSTREAM_FORBIDDEN, "), serverLog);
        assertTrue(serverLog.contains("[server] ru.it_spectrum.ai.loki.mcp.config.QueryToolsConfig - Tool listConnections {} -> ok, "), "MDC restored between calls: " + serverLog);
    }

    private static String text(JsonNode result) {
        var content = result.path("content");
        assertEquals(1, content.size(), result.toString());
        assertEquals("text", content.get(0).path("type").asText());
        return content.get(0).path("text").asText();
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
