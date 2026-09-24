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
    private static final String RULE = """
            {"id":"flyway","category":"startup","match":{"message":"Schema \\"(?<schema>[^\\"]+)\\""},
             "subject":"schema ${schema}","advice":"Schema ${schema} is ahead.","filter":"!= \\"x\\""}""";
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
                       "tenant":"team-a","timezone":"Europe/Moscow","limits":{"maxEntries":7,"maxExportLines":2,"maxExportBytes":9}},
                  "b":{"url":"http://localhost:1","description":"Test", "auth":{
                       "type":"BASIC","username":"reader","password":"${PASSWORD}"}},
                  "c":{"url":"http://localhost:2","applicationPackages":["ru.it_spectrum.asv","com.example"]}
                }}
                """);
        var registry = new ConnectionRegistry(entries);
        assertEquals(List.of("a", "b", "c"), registry.list().stream().map(c -> c.name()).toList());
        assertEquals("private-token", registry.require("a").auth().token());
        assertEquals("https://example.invalid/loki-prefix/", registry.require("a").url().toString());
        assertEquals("Europe/Moscow", registry.require("a").timezone().getId());
        assertEquals("team-a", registry.require("a").tenant());
        assertEquals(7, registry.require("a").limits().maxEntries());
        assertEquals(2, registry.require("a").limits().maxExportLines());
        assertEquals(9, registry.require("a").limits().maxExportBytes());
        assertEquals(ConnectionLimits.DEFAULTS, registry.require("b").limits());
        assertEquals("p$ass\\word", registry.require("b").auth().password());
        assertEquals(ConnectionAuth.NONE, registry.require("c").auth());
        assertEquals("UTC", registry.require("c").timezone().getId());
        assertEquals(List.of("ru.it_spectrum.asv", "com.example"), registry.require("c").applicationPackages());
        assertEquals(List.of(), registry.require("a").applicationPackages());
        assertThrows(UnsupportedOperationException.class, () -> registry.list().clear());
        assertFalse(entries.toString().contains("private-token"));
        assertFalse(entries.getFirst().auth().toString().contains("private-token"));
        assertThrows(LokiOperationException.class, () -> new ConnectionRegistry(List.of(entries.getFirst(), entries.getFirst())));
    }

    @Test
    void loadsRulesFileRelativeToTheConnectionsFileOnceForSeveralConnections() throws Exception {
        Files.createDirectories(directory.resolve("rules"));
        Files.writeString(directory.resolve("rules/stand.json"), "{\"rules\":[" + RULE + "]}");
        var entries = load("""
                {"connections":{
                  "a":{"url":"http://localhost:1","rulesFile":"rules/stand.json"},
                  "b":{"url":"http://localhost:2","rulesFile":"rules/../rules/stand.json"},
                  "c":{"url":"http://localhost:3"}
                }}
                """);
        var rule = entries.getFirst().rules().getFirst();
        assertEquals("flyway", rule.id());
        assertEquals(LogRule.Category.STARTUP, rule.category());
        assertEquals("schema ${schema}", rule.subject());
        assertSame(entries.get(0).rules(), entries.get(1).rules());
        assertEquals(List.of(), entries.get(2).rules());
        var matcher = rule.message().matcher("Schema \"sbp\" has version 1.7");
        assertTrue(matcher.find());
        assertEquals("Schema sbp is ahead.", LogRule.expand(rule.advice(), matcher));
    }

    @Test
    void scopeAndLevelsAreLoadedAndChecked() throws Exception {
        var entries = load("""
                {"connections":{"a":{"url":"http://localhost:1","scope":"{namespace=\\"dev\\"}",
                  "levels":{"error":"|~ \\"ERROR|Exception\\""}}}}
                """);
        assertEquals("{namespace=\"dev\"}", entries.getFirst().scope());
        assertEquals("|~ \"ERROR|Exception\"", entries.getFirst().allLevels().get("error"));
        assertEquals("|~ \"WARN\"", entries.getFirst().allLevels().get("warn"));
        for (String bad : List.of("\"scope\":\"namespace=dev\"", "\"scope\":\"{a=\\\"b\\\"} |= \\\"x\\\"\"",
                "\"levels\":{\"error\":\"ERROR\"}", "\"levels\":{\"Error\":\"|= \\\"x\\\"\"}"))
            assertSafeConfigurationError(assertThrows(LokiOperationException.class,
                    () -> load("{\"connections\":{\"a\":{\"url\":\"http://localhost\"," + bad + "}}}")));
    }

    @Test
    void lineFormatsComeWithTheRulesFilesInOrder() throws Exception {
        Files.writeString(directory.resolve("stand.json"),
                "{\"formats\":[{\"id\":\"stand\",\"pattern\":\"^(?<level>\\\\w+) (?<message>.*)\"}],\"rules\":[]}");
        Files.writeString(directory.resolve("java.json"), "{\"formats\":[{\"id\":\"console\",\"pattern\":\"(?<message>.*)\"}],\"rules\":[]}");
        var entries = load("{\"connections\":{\"a\":{\"url\":\"http://localhost:1\",\"rulesFile\":[\"stand.json\",\"java.json\"]}}}");
        assertEquals(List.of("stand", "console"), entries.getFirst().formats().stream().map(LineFormat::id).toList());
        // A format without a message group, an invalid pattern and the same id in two files stop the start-up.
        Files.writeString(directory.resolve("again.json"), "{\"formats\":[{\"id\":\"stand\",\"pattern\":\"(?<message>.*)\"}],\"rules\":[]}");
        Files.writeString(directory.resolve("nomessage.json"), "{\"formats\":[{\"id\":\"x\",\"pattern\":\"(?<SECRET>.*)\"}],\"rules\":[]}");
        Files.writeString(directory.resolve("broken.json"), "{\"formats\":[{\"id\":\"x\",\"pattern\":\"(?<message>SECRET\"}],\"rules\":[]}");
        for (String files : List.of("[\"stand.json\",\"again.json\"]", "\"nomessage.json\"", "\"broken.json\""))
            assertSafeConfigurationError(assertThrows(LokiOperationException.class,
                    () -> load("{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"rulesFile\":" + files + "}}}")));
    }

    @Test
    void layoutsExportRootsAndExportLimitsAreLoadedAndChecked() throws Exception {
        Files.writeString(directory.resolve("stand.json"), "{\"layouts\":[{\"id\":\"spring\",\"template\":\"{level} {message}\"}],\"rules\":[]}");
        Files.writeString(directory.resolve("java.json"), "{\"layouts\":[{\"id\":\"spring\",\"template\":\"{message}\"},"
                + "{\"id\":\"short\",\"template\":\"{time:HH:mm} {message}\"}],\"rules\":[]}");
        Path path = directory.resolve("connections.json");
        Files.writeString(path, "{\"exportRoots\":[\"exports\",\"${ROOT}\"],\"connections\":{\"a\":{\"url\":\"http://localhost:1\","
                + "\"rulesFile\":[\"stand.json\",\"java.json\"],\"limits\":{\"maxExportLines\":10,\"maxExportBytes\":20}}}}");
        Path absolute = directory.resolve("elsewhere").toAbsolutePath();
        var config = ConnectionsLoader.loadConfig(path, name -> name.equals("ROOT") ? absolute.toString() : null);
        var a = config.connections().getFirst();
        // The stand's own file names the layout first; a generic file cannot replace it.
        assertEquals(List.of("spring:{level} {message}", "short:{time:HH:mm} {message}"),
                a.layouts().stream().map(l -> l.id() + ":" + l.template()).toList());
        assertEquals(10, a.limits().maxExportLines());
        assertEquals(20, a.limits().maxExportBytes());
        assertEquals(List.of(directory.resolve("exports").toAbsolutePath().normalize(), absolute), config.exportRoots());
        Files.writeString(path, "{\"connections\":{\"a\":{\"url\":\"http://localhost:1\"}}}");
        assertEquals(List.of(), ConnectionsLoader.loadConfig(path, name -> null).exportRoots());
        Files.writeString(directory.resolve("bad.json"), "{\"layouts\":[{\"id\":\"x\",\"template\":\"{message\"}],\"rules\":[]}");
        Files.writeString(directory.resolve("raw.json"), "{\"layouts\":[{\"id\":\"raw\",\"template\":\"{message}\"}],\"rules\":[]}");
        for (String json : List.of("{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"rulesFile\":\"bad.json\"}}}",
                "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"rulesFile\":\"raw.json\"}}}",
                "{\"exportRoots\":[],\"connections\":{\"a\":{\"url\":\"http://localhost\"}}}",
                "{\"exportRoots\":[\" \"],\"connections\":{\"a\":{\"url\":\"http://localhost\"}}}",
                "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"limits\":{\"maxExportLines\":0}}}}"))
            assertSafeConfigurationError(assertThrows(LokiOperationException.class, () -> load(json), json));
    }

    @Test
    void severalRulesFilesAreTriedInTheirOrderWithIdsUniqueAcrossThem() throws Exception {
        Files.writeString(directory.resolve("stand.json"), "{\"rules\":[" + RULE + "]}");
        Files.writeString(directory.resolve("java.json"),
                "{\"rules\":[{\"id\":\"redis\",\"category\":\"dependency\",\"match\":{\"exception\":\"^Redis\"},\"subject\":\"Redis\",\"advice\":\"a\"}]}");
        var entries = load("{\"connections\":{\"a\":{\"url\":\"http://localhost:1\",\"rulesFile\":[\"stand.json\",\"java.json\"]}}}");
        assertEquals(List.of("flyway", "redis"), entries.getFirst().rules().stream().map(LogRule::id).toList());
        // The same id in two files, an empty list and a list of anything but paths stop the start-up.
        Files.writeString(directory.resolve("again.json"), "{\"rules\":[" + RULE + "]}");
        for (String rulesFile : List.of("[\"stand.json\",\"again.json\"]", "[]", "[\"stand.json\",1]", "{\"a\":\"stand.json\"}", "[\" \"]"))
            assertSafeConfigurationError(assertThrows(LokiOperationException.class,
                    () -> load("{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"rulesFile\":" + rulesFile + "}}}")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"rules\":[{\"id\":\"a\",\"category\":\"weather\",\"match\":{\"message\":\"x\"},\"advice\":\"SECRET\"}]}",
            "{\"rules\":[{\"id\":\"a\",\"category\":\"noise\",\"match\":{\"message\":\"SECRET(\"},\"advice\":\"a\"}]}",
            "{\"rules\":[{\"id\":\"a\",\"category\":\"noise\",\"match\":{},\"advice\":\"SECRET\"}]}",
            "{\"rules\":[{\"id\":\"a\",\"category\":\"noise\",\"match\":{\"message\":\"x\"}}]}",
            "{\"rules\":[{\"id\":\"a\",\"category\":\"dependency\",\"match\":{\"message\":\"x\"},\"advice\":\"SECRET\"}]}",
            "{\"rules\":[{\"id\":\"a\",\"category\":\"noise\",\"match\":{\"message\":\"x\"},\"advice\":\"${host} SECRET\"}]}",
            "{\"rules\":[{\"id\":\"a\",\"category\":\"noise\",\"match\":{\"message\":\"x\"},\"advice\":\"a\",\"filter\":\"| json SECRET\"}]}",
            "{\"rules\":[{\"id\":\"A b\",\"category\":\"noise\",\"match\":{\"message\":\"x\"},\"advice\":\"SECRET\"}]}",
            "{\"rules\":[{\"id\":\"a\",\"category\":\"noise\",\"match\":{\"message\":\"x\"},\"advice\":\"a\"},"
                    + "{\"id\":\"a\",\"category\":\"noise\",\"match\":{\"message\":\"y\"},\"advice\":\"SECRET\"}]}",
            "{\"rules\":[{\"id\":\"a\",\"category\":\"noise\",\"match\":{\"message\":\"x\",\"SECRET\":\"y\"},\"advice\":\"a\"}]}",
            "{\"SECRET\":[]}"
    })
    void rejectsInvalidRulesWithoutLeakingSource(String rules) throws Exception {
        Files.writeString(directory.resolve("rules.json"), rules);
        assertSafeConfigurationError(assertThrows(LokiOperationException.class,
                () -> load("{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"rulesFile\":\"rules.json\"}}}")));
        assertSafeConfigurationError(assertThrows(LokiOperationException.class,
                () -> load("{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"rulesFile\":\"SECRET-missing.json\"}}}")));
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
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"applicationPackages\":[\"ru.SECRET*\"]}}}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"applicationPackages\":[\"ru..x\"]}}}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"applicationPackages\":[null]}}}",
            "{\"connections\":{\"a\":{\"url\":\"http://localhost\",\"applicationPackages\":\"ru.x\"}}}",
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
    @ValueSource(strings = {"\"maxExportLines\":0", "\"maxExportBytes\":-1",
            "\"maxExportLines\":1.5", "\"maxExportLines\":\"2\"", "\"maxMetricSeries\":2"})
    void rejectsInvalidOrUnknownLimits(String limits) {
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
