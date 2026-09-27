package ru.it_spectrum.ai.loki.mcp.connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;
import ru.it_spectrum.ai.loki.mcp.service.ConnectionsService;
import ru.it_spectrum.ai.loki.mcp.service.LokiOperationException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionsTest {
    @TempDir
    Path directory;

    private ConnectionsLoader.Config load(String json) throws Exception {
        Path file = directory.resolve("connections.json");
        Files.writeString(file, json);
        return ConnectionsLoader.loadConfig(file, name -> name.equals("TOKEN") ? "SECRET-token" : null);
    }

    @Test
    void loadsIsolatedConnectionsAndFormatsWithoutProbing() throws Exception {
        Files.writeString(directory.resolve("formats.json"), """
                {"formats":[{"id":"plain","pattern":"(?<level>INFO|ERROR) (?<message>.*)"}],
                 "layouts":[{"id":"short","template":"{time} {message}"}]}
                """);
        var config = load("""
                {"exportRoots":["exports"],"connections":{
                  "dev":{"description":"Development","hint":"Use {app=backend}.","url":"http://localhost:3100",
                         "timezone":"Europe/Moscow","serviceLabels":["app","job"],"formatFile":"formats.json",
                         "limits":{"maxExportLines":10,"maxExportBytes":20}},
                  "secured":{"url":"https://localhost:3101","auth":{"type":"BEARER","token":"${TOKEN}"}}}}
                """);
        assertEquals(2, config.connections().size());
        var dev = config.connections().getFirst();
        assertEquals(List.of("app", "job"), dev.serviceLabels());
        assertEquals("plain", dev.formats().getFirst().id());
        assertEquals("short", dev.layouts().getFirst().id());
        assertEquals(10, dev.limits().maxExportLines());
        assertEquals(20, dev.limits().maxExportBytes());
        assertEquals(List.of(directory.resolve("exports").toAbsolutePath().normalize()), config.exportRoots());
        var secured = config.connections().get(1);
        assertEquals(ConnectionAuth.Type.BEARER, secured.auth().type());
        assertEquals("SECRET-token", secured.auth().token());
        assertFalse(new ConnectionsService(new ConnectionRegistry(config.connections())).list().contains("SECRET"));
    }

    @Test
    void bundledExampleConfigurationLoadsWithPlaceholderValues() {
        var config = ConnectionsLoader.loadConfig(Path.of("examples", "connections.json"), name -> switch (name) {
            case "LOKI_DEV_URL", "LOKI_TST_URL", "LOKI_URL" -> "http://localhost:3100";
            case "LOKI_TOKEN" -> "sample-token";
            case "LOKI_TENANT" -> "sample-tenant";
            default -> null;
        });
        assertEquals(4, config.connections().size());
        var dev = config.connections().stream().filter(connection -> connection.name().equals("dev")).findFirst().orElseThrow();
        assertEquals(2, dev.formats().size());
        assertEquals("spring", dev.layouts().getFirst().id());
    }

    @Test
    void requiresExplicitExactConnectionName() throws Exception {
        var registry = new ConnectionRegistry(load("{\"connections\":{\"only\":{\"url\":\"http://localhost\"}}}").connections());
        assertEquals(ErrorCode.CONNECTION_REQUIRED, assertThrows(LokiOperationException.class,
                () -> registry.require(null)).error().code());
        assertEquals(ErrorCode.INVALID_CONNECTION, assertThrows(LokiOperationException.class,
                () -> registry.require(" only")).error().code());
        assertEquals(ErrorCode.UNKNOWN_CONNECTION, assertThrows(LokiOperationException.class,
                () -> registry.require("ONLY")).error().code());
        assertNotNull(registry.require("only"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null", "{}", "{\"connections\":{}}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"unknown\":\"SECRET\"}}}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"scope\":\"SECRET\"}}}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"rulesFile\":\"SECRET\"}}}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"limits\":{\"maxEntries\":0}}}}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"limits\":{\"maxEntries\":1.5}}}}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"auth\":{\"type\":\"BEARER\",\"token\":\"${MISSING}\"}}}}",
            "{\"exportRoots\":[],\"connections\":{\"a\":{\"url\":\"http://localhost\"}}}",
            "SECRET malformed json"
    })
    void rejectsInvalidConfigurationWithoutLeakingSource(String json) {
        var error = assertThrows(LokiOperationException.class, () -> load(json));
        assertEquals(ErrorCode.CONFIGURATION_ERROR, error.error().code());
        assertFalse(error.toString().contains("SECRET"));
    }

    @Test
    void rejectsBadFormatFilesAndUnsafeUrls() throws Exception {
        Files.writeString(directory.resolve("bad.json"), "{\"formats\":[{\"id\":\"bad\",\"pattern\":\"(\"}]}");
        assertThrows(LokiOperationException.class, () -> load(
                "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"formatFile\":\"bad.json\"}}}"));
        assertThrows(LokiOperationException.class, () -> load(
                "{\"connections\":{\"a\":{\"url\":\"http://user:SECRET@localhost\"}}}"));
        assertThrows(LokiOperationException.class, () -> load(
                "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"formatFile\":\"SECRET-missing.json\"}}}"));
    }
}
