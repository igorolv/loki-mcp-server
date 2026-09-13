package ru.it_spectrum.ai.loki.mcp.connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;
import ru.it_spectrum.ai.loki.mcp.service.Errors;
import ru.it_spectrum.ai.loki.mcp.service.LokiOperationException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionsTest {
    @TempDir
    Path directory;

    private List<ConnectionDefinition> load(String json) throws Exception {
        Path path = directory.resolve("connections.json");
        Files.writeString(path, json);
        return ConnectionsLoader.load(path, name -> switch (name) {
            case "TOKEN" -> "private-token";
            case "URL" -> "https://example.invalid/loki-prefix/";
            case "PASSWORD" -> "p$ass\\word";
            default -> null;
        });
    }

    @Test
    void loadsIndependentDefinitionsAndDefaultsWithoutConnecting() throws Exception {
        var entries = load("""
                {"connections":{
                  "a":{"url":"${URL}","auth":{"type":"BEARER","token":"${TOKEN}"},
                       "tenant":"team-a","timezone":"Europe/Moscow","limits":{"maxEntries":7,"maxMetricSeries":2,"maxMetricPoints":9}},
                  "b":{"url":"http://localhost:1","description":"Test", "auth":{
                       "type":"BASIC","username":"reader","password":"${PASSWORD}"}},
                  "c":{"url":"http://localhost:2"}
                }}
                """);
        var registry = new ConnectionRegistry(entries);
        assertEquals(List.of("a", "b", "c"), registry.list().stream().map(c -> c.name()).toList());
        assertEquals("private-token", registry.require("a").auth().token());
        assertEquals("https://example.invalid/loki-prefix/", registry.require("a").url().toString());
        assertEquals("Europe/Moscow", registry.require("a").timezone().getId());
        assertEquals("team-a", registry.require("a").tenant());
        assertEquals(7, registry.require("a").limits().maxEntries());
        assertEquals(2, registry.require("a").limits().maxMetricSeries());
        assertEquals(9, registry.require("a").limits().maxMetricPoints());
        assertEquals(ConnectionLimits.DEFAULTS, registry.require("b").limits());
        assertEquals("p$ass\\word", registry.require("b").auth().password());
        assertEquals(ConnectionAuth.NONE, registry.require("c").auth());
        assertEquals("UTC", registry.require("c").timezone().getId());
        assertThrows(UnsupportedOperationException.class, () -> registry.list().clear());
        assertFalse(entries.toString().contains("private-token"));
        assertFalse(entries.getFirst().auth().toString().contains("private-token"));
        assertThrows(LokiOperationException.class, () -> new ConnectionRegistry(List.of(entries.getFirst(), entries.getFirst())));
    }

    @Test
    void requiresExplicitExactNameEvenWithSingleConnection() throws Exception {
        var registry = new ConnectionRegistry(load("""
                {"connections":{"only":{"url":"http://localhost:1"}}}
                """));
        for (String name : new String[]{null, "", "  "}) {
            assertEquals(ErrorCode.CONNECTION_REQUIRED,
                    assertThrows(LokiOperationException.class, () -> registry.require(name)).error().code());
        }
        for (String name : new String[]{" only", "only ", "x\nSECRET", "a/b"}) {
            assertEquals(ErrorCode.INVALID_CONNECTION,
                    assertThrows(LokiOperationException.class, () -> registry.require(name)).error().code());
        }
        assertEquals(ErrorCode.UNKNOWN_CONNECTION,
                assertThrows(LokiOperationException.class, () -> registry.require("ONLY")).error().code());
        assertNotNull(registry.require("only"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null", "{}", "{\"connections\":{}}", "{\"connections\":{\"a\":null}}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\"},\"a\":{\"url\":\"http://localhost\"}}}",
            "{\"connections\":{\"bad name\":{\"url\":\"http://localhost\"}}}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"unknown\":\"SECRET\"}}}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\"}}} {}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"auth\":{\"type\":\"SECRET\"}}}}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"auth\":{\"type\":\"BEARER\",\"token\":\"${MISSING}\"}}}}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"auth\":{\"type\":\"BASIC\",\"username\":\"u\"}}}}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"auth\":{\"type\":\"BASIC\",\"username\":\"u\",\"password\":\"SECRET\",\"token\":\"SECRET\"}}}}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"tenant\":\"SECRET\\r\\nheader\"}}}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"timezone\":\"SECRET\"}}}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"limits\":{\"maxEntries\":0}}}}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"limits\":{\"maxEntries\":1.5}}}}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"limits\":{\"maxEntries\":\"10\"}}}}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"limits\":{\"requestTimeoutMs\":-1}}}}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"limits\":{\"maxResponseBytes\":5}}}}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"limits\":{\"maxIntervalSeconds\":0}}}}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"auth\":{\"type\":\"NONE\",\"token\":\"SECRET\"}}}}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"auth\":{\"type\":\"BEARER\",\"token\":\"SECRET\\n\"}}}}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"auth\":{\"type\":\"BEARER\",\"token\":\"${BROKEN\"}}}}",
            "SECRET malformed json"
    })
    void rejectsInvalidConfigurationWithoutLeakingSource(String json) {
        var failure = assertThrows(LokiOperationException.class, () -> load(json));
        assertSafeConfigurationError(failure);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///SECRET", "http://user:SECRET@localhost", "https://localhost/?token=SECRET",
            "https://localhost/#SECRET", "relative/SECRET", "http://localhost:99999", "http://localhost:0", "${MISSING}"})
    void rejectsUnsafeUrls(String url) {
        assertSafeConfigurationError(assertThrows(LokiOperationException.class, () -> load(
                "{\"connections\":{\"a\":{\"url\":\"" + url + "\"}}}")));
    }

    @Test
    void rejectsMissingAndOversizedFiles() throws Exception {
        assertSafeConfigurationError(assertThrows(LokiOperationException.class,
                () -> ConnectionsLoader.load(directory.resolve("SECRET-missing"), key -> null)));
        assertSafeConfigurationError(assertThrows(LokiOperationException.class, () -> load(" ".repeat(1024 * 1024 + 1))));
    }

    @Test
    void unexpectedErrorsHaveControlledPayload() {
        var error = Errors.from(new IllegalArgumentException("SECRET"));
        assertEquals(ErrorCode.INTERNAL_ERROR, error.code());
        assertFalse(error.message().contains("SECRET"));
        assertFalse(error.retryable());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"maxMetricSeries\":0", "\"maxMetricPoints\":-1",
            "\"maxMetricPoints\":1.5", "\"maxMetricSeries\":\"2\""})
    void rejectsInvalidMetricLimits(String limits) {
        assertSafeConfigurationError(assertThrows(LokiOperationException.class, () -> load(
                "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"limits\":{" + limits + "}}}}")));
    }

    private void assertSafeConfigurationError(LokiOperationException failure) {
        assertEquals(ErrorCode.CONFIGURATION_ERROR, failure.error().code());
        assertNull(failure.getCause());
        var stack = new StringWriter();
        failure.printStackTrace(new PrintWriter(stack));
        assertFalse(stack.toString().contains("SECRET"));
    }
}
