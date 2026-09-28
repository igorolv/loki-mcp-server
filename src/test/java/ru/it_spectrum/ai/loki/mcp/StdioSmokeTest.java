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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

class StdioSmokeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

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

    private static void send(BufferedWriter input, String json) throws Exception {
        input.write(json.strip());
        input.newLine();
        input.flush();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(60)
    void executableJarSpeaksOnlyJsonRpcOnStdout(boolean overrideFile) throws Exception {
        var upstream = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var sawLookaheadLimit = new AtomicBoolean();
        var sawSeriesMatch = new AtomicBoolean();
        upstream.createContext("/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                String query = java.net.URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
                int status = query.contains("fail") ? 403 : query.contains("broken") ? 400
                                                            : path.endsWith("/label/blocked/values") ? 404 : 200;
                String body;
                if (status == 403) body = "SECRET_TOKEN upstream error";
                else if (status == 400) body = "parse error at line 1, col 9: syntax error";
                else if (status == 404) body = "SECRET_TOKEN path blocked";
                else if (path.endsWith("/labels")) body = "{\"status\":\"success\",\"data\":[\"kind\"]}";
                else if (path.endsWith("/values")) body = "{\"status\":\"success\",\"data\":[\"test\"]}";
                else if (path.endsWith("/series")) {
                    sawSeriesMatch.set(query.contains("match[]={kind=\"test\"}") && !query.contains("limit="));
                    body = "{\"status\":\"success\",\"data\":[{\"kind\":\"test\",\"namespace\":\"a\"},{\"kind\":\"test\",\"namespace\":\"b\"}]}";
                }
                else if (path.endsWith("/query"))
                    body = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{\"kind\":\"test\"},\"value\":[1700000000.125,\"3\"]}]}}";
                else if (query.contains("step="))
                    body = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"metric\":{\"kind\":\"test\"},\"values\":[[1700000000.125,\"NaN\"]]}],\"stats\":{\"summary\":{\"totalLinesProcessed\":200}}}}";
                else if (query.contains("large")) body = mapper.writeValueAsString(Map.of("status", "success", "data",
                        Map.of("resultType", "streams", "result", List.of(Map.of("stream", Map.of("kind", "test"),
                                "values", java.util.stream.IntStream.range(0, 12).mapToObj(i -> List.of("170000000012345678" + (i % 10),
                                        mapper.writeValueAsString(Map.of("message", "Ошибка 🐈\"\\\n".repeat(1000))))).toList())))));
                else if (query.contains("lookahead")) {
                    sawLookaheadLimit.set(query.contains("limit=2"));
                    body = """
                            {"status":"success","data":{"resultType":"streams","result":[
                              {"stream":{"kind":"test"},"values":[
                                ["1700000000123456788","older"],["1700000000123456789","newer"]
                              ]}
                            ]}}
                            """;
                }
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
                  "test":{"url":"http://127.0.0.1:%d","limits":{"maxResponseBytes":6000,
                    "maxDiscoveryIntervalSeconds":432000,"maxIntervalSeconds":172800,
                    "maxCountIntervalSeconds":259200,"maxTimeCountIntervalSeconds":345600,
                    "maxEntries":25,"requestTimeoutMs":4500},"serviceLabels":["kind"]},
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
            assertEquals(5, catalog.size());
            var names = new HashSet<String>();
            for (var declaration : catalog) {
                names.add(declaration.path("name").asText());
                assertFalse(declaration.has("outputSchema"), declaration.toString());
                assertTrue(declaration.path("description").asText().length() > 80, declaration.toString());
                // exportLogs writes local files; every other tool only reads.
                assertEquals(!declaration.path("name").asText().equals("exportLogs"), declaration.path("annotations").path("readOnlyHint").asBoolean());
                assertFalse(declaration.path("annotations").path("destructiveHint").asBoolean());
                assertEquals(!declaration.path("name").asText().equals("listConnections"),
                        declaration.path("annotations").path("openWorldHint").asBoolean());
                assertEquals("object", declaration.path("inputSchema").path("type").asText());
            }
            assertEquals(new HashSet<>(List.of("listConnections", "discoverLogs", "countLogs", "queryLogs", "exportLogs")), names);
            JsonNode tool = StreamSupport.stream(catalog.spliterator(), false)
                    .filter(t -> t.path("name").asText().equals("queryLogs")).findFirst().orElseThrow();
            assertEquals(List.of("connection", "query"), mapper.convertValue(tool.path("inputSchema").path("required"), List.class));
            assertFalse(tool.path("inputSchema").path("properties").has("service"), tool.toString());
            assertTrue(tool.path("inputSchema").path("properties").has("order"), tool.toString());
            for (String name : List.of("countLogs", "exportLogs")) {
                JsonNode schema = StreamSupport.stream(catalog.spliterator(), false)
                        .filter(declaration -> declaration.path("name").asText().equals(name)).findFirst().orElseThrow()
                        .path("inputSchema");
                assertEquals(List.of("connection", "query"), mapper.convertValue(schema.path("required"), List.class));
                if (name.equals("countLogs")) assertTrue(schema.path("properties").has("step"), schema.toString());
                if (name.equals("exportLogs")) assertFalse(schema.path("properties").has("splitByService"), schema.toString());
            }
            JsonNode discovery = StreamSupport.stream(catalog.spliterator(), false)
                    .filter(declaration -> declaration.path("name").asText().equals("discoverLogs")).findFirst().orElseThrow()
                    .path("inputSchema");
            assertFalse(discovery.path("properties").has("selector"), discovery.toString());
            assertTrue(discovery.path("properties").has("match"), discovery.toString());
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
                assertEquals("""
                        dev — Development. Labels: kind, level.
                          Limits: discoverLogs 7d (labels/values), 1d (match); queryLogs 1d, max 1000 lines; countLogs 1d (total/label/label+time), 7d (time-only); exportLogs 1d; request timeout 30s.
                        test.
                          Limits: discoverLogs 5d (labels/values), 1d (match); queryLogs 2d, max 25 lines; countLogs 3d (total/label/label+time), 4d (time-only); exportLogs 2d; request timeout 4500ms.
                        tiny.
                          Limits: discoverLogs 7d (labels/values), 1d (match); queryLogs 1d, max 1000 lines; countLogs 1d (total/label/label+time), 7d (time-only); exportLogs 1d; request timeout 30s.
                        """.stripTrailing(), text(result));
                assertNoSecrets(call.toString());
            }
            // Oversized payloads and the minimum budget traverse the real outbound transport.
            for (int i = 0; i < 16; i++) {
                String name = i % 4 == 2 ? "countLogs" : i % 4 == 3 ? "discoverLogs" : "queryLogs";
                var args = new HashMap<String, Object>();
                args.put("connection", i % 4 == 0 ? "tiny" : "test");
                args.put("start", "1700000000000000000");
                args.put("end", "1700000001000000000");
                if (name.equals("countLogs")) args.put("query", "{kind=\"large\"}");
                else if (name.equals("queryLogs")) {
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
                    if (index % 4 == 2) assertTrue(text.startsWith("3 lines match {kind=\"large\"}"), text);
                    if (index % 4 == 3)
                        assertTrue(text.startsWith("Labels — test, 2023-11-14"), text);
                }
            }
            for (int id = 35; id < 51; id++) {
                String name = id % 4 == 1 ? "countLogs" : "queryLogs";
                var arguments = new HashMap<String, Object>(Map.of("connection", "test"));
                arguments.putAll(Map.of("query", "{kind=\"test\"}", "start", "1700000000000000000", "end", "1700000001000000000"));
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
                } else if (id % 8 == 7) {
                    assertTrue(text.contains("\n22:13:20.123 {kind=\"test\", level=\"error\"}  Ошибка 🐈\nShown all 1 matching lines."), text);
                } else {
                    assertEquals("{kind=\"test\"} — test, 2023-11-14 22:13:20–22:13:21 (Z), all 1 lines:\n22:13:20.123 ERROR test  Ошибка 🐈\nShown all 1 matching lines.", text);
                }
                assertNoSecrets(call.toString());
            }
            send(input, mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 60, "method", "tools/call",
                    "params", Map.of("name", "queryLogs", "arguments", Map.of("connection", "test",
                            "query", "{kind=\"test\"}", "start", "1700000000000000000",
                            "end", "1700000001000000000", "order", "oldest")))));
            var forward = response(stdout, stderr).path("result");
            assertFalse(forward.path("isError").asBoolean(), text(forward));
            assertTrue(text(forward).contains("Ошибка 🐈"), text(forward));
            send(input, mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 61, "method", "tools/call",
                    "params", Map.of("name", "countLogs", "arguments", Map.of("connection", "test",
                            "query", "{kind=\"test\"}", "start", "1699999999000000000",
                            "end", "1700000001000000000", "groupBy", "kind,time", "step", "1s")))));
            var grouped = response(stdout, stderr).path("result");
            assertFalse(grouped.path("isError").asBoolean(), text(grouped));
            assertTrue(text(grouped).contains("By kind and time (1s buckets, bucket start):"), text(grouped));
            assertNoSecrets(text(forward) + text(grouped));
            send(input, mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 62, "method", "tools/call",
                    "params", Map.of("name", "queryLogs", "arguments", Map.of("connection", "test",
                            "query", "{kind=\"test\"} |= \"lookahead\"", "start", "1700000000000000000",
                            "end", "1700000001000000000", "limit", 1)))));
            var lookedAhead = response(stdout, stderr).path("result");
            assertFalse(lookedAhead.path("isError").asBoolean(), text(lookedAhead));
            assertTrue(sawLookaheadLimit.get());
            assertTrue(text(lookedAhead).contains("newest 1 lines (more exist):"), text(lookedAhead));
            assertTrue(text(lookedAhead).contains("newer"), text(lookedAhead));
            assertFalse(text(lookedAhead).contains("older"), text(lookedAhead));
            assertNoSecrets(text(lookedAhead));
            for (int id = 70; id < 86; id++) {
                send(input, mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "method", "tools/call",
                        "params", Map.of("name", "discoverLogs", "arguments", id % 2 == 0
                                ? Map.of("connection", "test", "start", "1700000000000000000", "end", "1700000001000000000")
                                : Map.of("connection", "test", "label", "blocked", "start", "now-1s", "end", "now")))));
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
                    assertTrue(text.startsWith("Labels — test, 2023-11-14 22:13:20–22:13:21 (Z): 1.\nkind\n"), text);
                } else {
                    assertTrue(result.path("isError").asBoolean(), text);
                    assertTrue(text.startsWith("Error ENDPOINT_UNAVAILABLE"), text);
                }
                assertNoSecrets(call.toString());
            }
            for (int id = 87; id <= 88; id++) {
                var arguments = new HashMap<String, Object>(Map.of("connection", "test", "match", "{kind=\"test\"}",
                        "start", "1700000000000000000", "end", "1700000001000000000"));
                if (id == 88) arguments.put("label", "namespace");
                send(input, mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "method", "tools/call",
                        "params", Map.of("name", "discoverLogs", "arguments", arguments))));
                var result = response(stdout, stderr).path("result");
                assertFalse(result.path("isError").asBoolean(), text(result));
                if (id == 87) assertTrue(text(result).contains("2 label sets returned by Loki.\n{kind=\"test\", namespace=\"a\"}"), text(result));
                else assertTrue(text(result).contains("2 values from 2 label sets returned by Loki.\n\"a\"\n\"b\""), text(result));
                assertNoSecrets(result.toString());
            }
            assertTrue(sawSeriesMatch.get());
            for (var bad : List.of(Map.of("label", "kind"),
                    Map.of("connection", "test", "label", "kind", "start", "MODEL_ARGUMENT"))) {
                send(input, mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 86, "method", "tools/call",
                        "params", Map.of("name", "discoverLogs", "arguments", bad))));
                var result = response(stdout, stderr).path("result");
                assertTrue(result.path("isError").asBoolean());
                assertTrue(text(result).startsWith(bad.containsKey("connection") ? "Error INVALID_ARGUMENT: Cannot parse time"
                        : "Tool (discoverLogs) input validation failed: Validation failed: JSON schema validation errors: [: required property 'connection' not found]"), text(result));
            }
            // Missing and mistyped arguments are answered by the SDK input validation before the tool is called.
            String[] errors = {"Tool (queryLogs) input validation failed", "Error UNKNOWN_CONNECTION", "Error UPSTREAM_FORBIDDEN", "Error INVALID_ARGUMENT: limit must be",
                    "Tool (queryLogs) input validation failed", "Error UPSTREAM_BAD_REQUEST: Loki rejected the query: parse error at line 1, col 9: syntax error"};
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
            // exportLogs writes the lines into the default export directory and answers with the path, never the lines;
            // {line} decorates a plain line no format splits, which a template without it would write unchanged.
            send(input, mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 90, "method", "tools/call", "params", Map.of("name", "exportLogs",
                    "arguments", Map.of("connection", "test", "query", "{kind=\"test\"}", "start", "now-1s", "format", "{level} {line}")))));
            var exported = response(stdout, stderr);
            String report = text(exported.path("result"));
            assertFalse(exported.path("result").path("isError").asBoolean(), report);
            Path exports = temporaryDirectory.resolve("data").toAbsolutePath().resolve("exports");
            try (var files = Files.list(exports)) {
                var file = files.findFirst().orElseThrow();
                assertEquals(List.of("ERROR Ошибка 🐈"), Files.readAllLines(file, StandardCharsets.UTF_8));
                assertTrue(report.contains("File: " + file), report);
            }
            assertFalse(report.contains("Ошибка"), report);
            assertNoSecrets(exported.toString());
            Path requested = temporaryDirectory.resolve("requested-exports").toAbsolutePath();
            send(input, mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 91, "method", "tools/call",
                    "params", Map.of("name", "exportLogs", "arguments", Map.of("connection", "test",
                            "query", "{kind=\"test\"}", "start", "now-1s", "directory", requested.toString())))));
            var requestedExport = response(stdout, stderr);
            String requestedReport = text(requestedExport.path("result"));
            assertFalse(requestedExport.path("result").path("isError").asBoolean(), requestedReport);
            try (var files = Files.list(requested)) {
                var file = files.findFirst().orElseThrow();
                assertEquals(List.of("Ошибка 🐈"), Files.readAllLines(file, StandardCharsets.UTF_8));
                assertTrue(requestedReport.contains("File: " + file), requestedReport);
            }
            assertNoSecrets(requestedExport.toString());
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
        assertTrue(serverLog.contains("[test] ru.it_spectrum.ai.loki.mcp.tools.ToolCallDiagnostics - Tool queryLogs {end=1700000001000000000, query={kind=\"test\"}, start=1700000000000000000} -> ok, "), serverLog);
        assertTrue(serverLog.contains("[test] ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient - GET /loki/api/v1/query_range {start=1700000000000000000, end=1700000001000000000, query={kind=\"test\"}, limit=25, direction=backward} -> 200, "), serverLog);
        assertTrue(serverLog.contains("Tool queryLogs {end=now, limit=0, query={kind=\"test\"}, start=now-1s} -> INVALID_ARGUMENT, "), serverLog);
        assertTrue(serverLog.contains("GET /loki/api/v1/query_range {") && serverLog.contains("-> UPSTREAM_FORBIDDEN, "), serverLog);
        assertTrue(serverLog.contains("[test] ru.it_spectrum.ai.loki.mcp.tools.ToolCallDiagnostics - Tool queryLogs {end=now, query={kind=\"fail\"}, start=now-1s} -> UPSTREAM_FORBIDDEN, "), serverLog);
        // A call rejected by the SDK input validation never reaches the tool; the SDK logs it instead.
        assertTrue(serverLog.contains("WARN  [server] io.modelcontextprotocol.util.ToolInputValidator - Tool (queryLogs) input validation failed: "), serverLog);
        assertTrue(serverLog.contains("[server] ru.it_spectrum.ai.loki.mcp.tools.ToolCallDiagnostics - Tool listConnections {} -> ok, "), "MDC restored between calls: " + serverLog);
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
}
