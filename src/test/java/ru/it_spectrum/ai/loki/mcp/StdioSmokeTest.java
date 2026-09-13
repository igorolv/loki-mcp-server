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

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
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
                  "test":{"url":"http://127.0.0.1:2"}
                }}
                """);
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
            assertEquals(1, catalog.size());
            JsonNode tool = catalog.get(0);
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
                assertEquals(2, payload.path("connections").size());
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
        } finally {
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
