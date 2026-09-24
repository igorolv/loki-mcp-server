package ru.it_spectrum.ai.loki.mcp.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionAuth;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionDefinition;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionLimits;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;
import ru.it_spectrum.ai.loki.mcp.service.Errors;
import ru.it_spectrum.ai.loki.mcp.service.LokiOperationException;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static ru.it_spectrum.ai.loki.mcp.client.LokiResponses.QueryResponse;
import static ru.it_spectrum.ai.loki.mcp.client.LokiResponses.Streams;
import static ru.it_spectrum.ai.loki.mcp.model.ErrorCode.*;

@Timeout(15)
class LokiHttpClientTest {
    private static final Instant START = Instant.parse("2024-07-03T09:46:40.123456789Z");
    private static final Instant END = START.plusSeconds(30);
    private static final String EMPTY = "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[]}}";
    private final List<LokiHttpClient> clients = new ArrayList<>();
    private final BlockingQueue<Captured> requests = new LinkedBlockingQueue<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private HttpServer server;
    private ExecutorService executor;
    private volatile HttpHandler handler;

    private static void respond(HttpExchange exchange, int status, String body, boolean chunked) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, chunked ? 0 : bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        handler = exchange -> respond(exchange, 200, EMPTY, false);
        server.createContext("/", exchange -> {
            try (exchange) {
                requests.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("X-Scope-OrgID"),
                        exchange.getRequestHeaders().getFirst("Accept")));
                handler.handle(exchange);
            } catch (IOException ignored) { /* Client cancellation is expected in timeout/budget tests. */ }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        release.countDown();
        clients.forEach(LokiHttpClient::close);
        server.stop(0);
        executor.shutdownNow();
    }

    private ConnectionDefinition definition(String name, String prefix, ConnectionAuth auth, String tenant,
                                            int bodyLimit, int timeout) {
        return new ConnectionDefinition(name, null, URI.create("http://127.0.0.1:"
                + server.getAddress().getPort() + prefix), auth, tenant, ZoneId.of("UTC"),
                new ConnectionLimits(200, timeout, bodyLimit, 1024, 100, 3600));
    }

    private LokiHttpClient client(ConnectionDefinition... definitions) {
        var client = new LokiHttpClient(new ConnectionRegistry(List.of(definitions)));
        clients.add(client);
        return client;
    }

    private LokiHttpClient plain(int bodyLimit, int timeout) {
        return client(definition("local", "", ConnectionAuth.NONE, null, bodyLimit, timeout));
    }

    private QueryResponse range(LokiHttpClient client) {
        return client.queryRange("local", "{app=\"test\"}", START, END, 10, LokiHttpClient.Direction.BACKWARD, null);
    }

    @Test
    void sendsEncodedReadOnlyRequestsWithPrefixAuthAndExactTimes() throws Exception {
        var client = client(definition("local", "/prefix%20space/", new ConnectionAuth(ConnectionAuth.Type.BASIC,
                "reader", "secret-password", null), "tenant-a", 8192, 3000));
        String query = "{app=\"a+b & / ? # %\"} |= \"ошибка 😀\"";
        client.queryRange("local", query, START, END, 17, LokiHttpClient.Direction.FORWARD, new BigDecimal("0.125"));
        Captured request = requests.poll(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("GET", request.method());
        assertEquals("/prefix%20space/loki/api/v1/query_range", request.uri().getRawPath());
        assertEquals("Basic " + Base64.getEncoder().encodeToString("reader:secret-password".getBytes(StandardCharsets.UTF_8)), request.auth());
        assertEquals("tenant-a", request.tenant());
        assertEquals("application/json", request.accept());
        assertEquals(query, parameter(request, "query"));
        assertEquals("1720000000123456789", parameter(request, "start"));
        assertEquals("1720000030123456789", parameter(request, "end"));
        assertEquals("17", parameter(request, "limit"));
        assertEquals("forward", parameter(request, "direction"));
        assertEquals("0.125", parameter(request, "step"));
        assertNull(request.uri().getFragment());
        client.queryInstant("local", "sum(rate({app=\"x\"}[1m]))", START);
        request = requests.poll(2, TimeUnit.SECONDS);
        assertEquals("/prefix%20space/loki/api/v1/query", request.uri().getRawPath());
        assertEquals("1720000000123456789", parameter(request, "time"));
        assertNull(parameter(request, "start"));
    }

    @Test
    void requestsMetadataWithExplicitWindowAndRepeatedSelectors() throws Exception {
        var client = plain(8192, 3000);
        handler = exchange -> respond(exchange, 200, "{\"status\":\"success\",\"data\":[\"pod\",\"app\"]}", false);
        assertEquals(List.of("pod", "app"), client.labels("local", START, END, null).values());
        Captured request = requests.poll(2, TimeUnit.SECONDS);
        assertEquals("/loki/api/v1/labels", request.uri().getPath());
        assertEquals("1720000000123456789", parameter(request, "start"));
        assertNull(parameter(request, "query"));
        client.labelValues("local", "service_name", START, END, "{pod=\"a+b\"}");
        request = requests.poll(2, TimeUnit.SECONDS);
        assertEquals("/loki/api/v1/label/service_name/values", request.uri().getPath());
        assertEquals("{pod=\"a+b\"}", parameter(request, "query"));
        handler = exchange -> respond(exchange, 200, "{\"status\":\"success\",\"data\":[{\"pod\":\"a\"}]}", false);
        var selectors = List.of("{pod=\"a\"}", "{pod=\"b\"}");
        assertEquals(Map.of("pod", "a"), client.series("local", selectors, START, END).streams().getFirst());
        request = requests.poll(2, TimeUnit.SECONDS);
        assertEquals("/loki/api/v1/series", request.uri().getPath());
        assertEquals(selectors, parameters(request, "match[]"));
    }

    @Test
    void keepsAuthorizationTenantAndFailuresIsolatedAcrossConnections() throws Exception {
        var client = client(
                definition("a", "/a", new ConnectionAuth(ConnectionAuth.Type.BEARER, null, null, "secret-token"), "tenant-a", 8192, 3000),
                definition("b", "/b", ConnectionAuth.NONE, null, 8192, 3000));
        handler = exchange -> {
            if (exchange.getRequestURI().getPath().startsWith("/a"))
                respond(exchange, 401, "SECRET secret-token tenant-a", false);
            else respond(exchange, 200, EMPTY, false);
        };
        var failed = executor.submit(() -> assertThrows(LokiOperationException.class, () -> client.queryInstant("a", "SECRET query", START)));
        var succeeded = executor.submit(() -> client.queryInstant("b", "valid", START));
        assertEquals(UPSTREAM_UNAUTHORIZED, failed.get(4, TimeUnit.SECONDS).error().code());
        assertInstanceOf(Streams.class, succeeded.get(4, TimeUnit.SECONDS).data());
        for (int i = 0; i < 2; i++) {
            Captured request = requests.poll(2, TimeUnit.SECONDS);
            if (request.uri().getPath().startsWith("/a")) {
                assertEquals("Bearer secret-token", request.auth());
                assertEquals("tenant-a", request.tenant());
            } else {
                assertNull(request.auth());
                assertNull(request.tenant());
            }
        }
        handler = exchange -> respond(exchange, 200, EMPTY, false);
        assertNotNull(client.queryInstant("a", "valid", START));
    }

    @Test
    void badRequestBodyIsPassedOnAsTheQueryError() {
        handler = exchange -> respond(exchange, 400, "parse error at line 1, col 23: syntax error: unexpected IDENTIFIER\n\u0007", false);
        var error = assertThrows(LokiOperationException.class, () -> range(plain(256, 3000)));
        assertEquals(UPSTREAM_BAD_REQUEST, error.error().code());
        assertEquals("Loki rejected the query: parse error at line 1, col 23: syntax error: unexpected IDENTIFIER", error.error().message());
        handler = exchange -> respond(exchange, 400, "{\"status\":\"error\",\"errorType\":\"bad_data\",\"error\":\"" + "x".repeat(500) + "\"}", false);
        var json = assertThrows(LokiOperationException.class, () -> range(plain(1024, 3000)));
        assertEquals("Loki rejected the query: " + "x".repeat(400) + "…", json.error().message());
        handler = exchange -> respond(exchange, 400, "", false);
        assertEquals("Loki rejected the query; check its syntax.", assertThrows(LokiOperationException.class, () -> range(plain(256, 3000))).error().message());
    }

    @ParameterizedTest
    @CsvSource({"401,UPSTREAM_UNAUTHORIZED,false", "403,UPSTREAM_FORBIDDEN,false",
            "404,ENDPOINT_UNAVAILABLE,false", "408,UPSTREAM_TIMEOUT,true", "429,UPSTREAM_RATE_LIMITED,true",
            "500,UPSTREAM_UNAVAILABLE,true", "503,UPSTREAM_UNAVAILABLE,true", "504,UPSTREAM_TIMEOUT,true",
            "302,UPSTREAM_HTTP_ERROR,false", "204,UPSTREAM_HTTP_ERROR,false", "418,UPSTREAM_HTTP_ERROR,false"})
    void mapsHttpErrorsWithoutExposingBodyOrFollowingRedirects(int status, ErrorCode code, boolean retryable) {
        handler = exchange -> {
            exchange.getResponseHeaders().set("Location", "/SECRET-redirect");
            respond(exchange, status, "SECRET upstream error including credentials", false);
        };
        var error = assertThrows(LokiOperationException.class, () -> range(plain(256, 3000)));
        assertSafe(error, code);
        assertEquals(retryable, error.error().retryable());
        assertEquals(1, requests.size(), "No redirect or retry request expected");
    }

    @Test
    void unavailableMetadataEndpointDoesNotDisableQuery() {
        var client = plain(8192, 3000);
        handler = exchange -> respond(exchange, exchange.getRequestURI().getPath().endsWith("labels") ? 404 : 200, EMPTY, false);
        assertSafe(assertThrows(LokiOperationException.class, () -> client.labels("local", START, END, null)), ENDPOINT_UNAVAILABLE);
        assertNotNull(range(client));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void enforcesBodyBudgetWithAndWithoutContentLength(boolean chunked) {
        handler = exchange -> respond(exchange, 200, "SECRET".repeat(1000), chunked);
        assertSafe(assertThrows(LokiOperationException.class, () -> range(plain(100, 3000))), UPSTREAM_RESPONSE_TOO_LARGE);
    }

    @Test
    void measuresBudgetInUtf8BytesAndAcceptsExactBoundary() {
        String json = "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[{\"stream\":{},\"values\":[[\"1\",\"😀漢字\"]]}]}}";
        handler = exchange -> respond(exchange, 200, json, true);
        int length = json.getBytes(StandardCharsets.UTF_8).length;
        assertEquals("😀漢字", assertInstanceOf(Streams.class, range(plain(length, 3000)).data()).streams().getFirst().entries().getFirst().line());
        assertSafe(assertThrows(LokiOperationException.class, () -> range(plain(length - 1, 3000))), UPSTREAM_RESPONSE_TOO_LARGE);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deadlineCoversBothHeadersAndStalledBody(boolean sendHeaders) {
        handler = exchange -> {
            if (sendHeaders) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
            }
            awaitRelease();
        };
        var client = plain(8192, 300);
        assertTimeout(Duration.ofSeconds(3), () ->
                assertSafe(assertThrows(LokiOperationException.class, () -> range(client)), UPSTREAM_TIMEOUT));
    }

    @Test
    void connectTimeoutCoversStalledTlsHandshake() throws Exception {
        try (var socket = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            var accepted = executor.submit(() -> {
                try (var peer = socket.accept()) {
                    awaitRelease();
                }
                return null;
            });
            var connection = new ConnectionDefinition("local", null, URI.create("https://127.0.0.1:" + socket.getLocalPort()),
                    ConnectionAuth.NONE, null, ZoneId.of("UTC"), new ConnectionLimits(200, 6000, 8192, 1024, 100, 3600));
            var client = client(connection);
            assertTimeout(Duration.ofSeconds(3), () ->
                    assertSafe(assertThrows(LokiOperationException.class, () -> range(client)), UPSTREAM_TIMEOUT));
            release.countDown();
            accepted.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void interruptionCancelsRequestAndPreservesInterruptFlag() throws Exception {
        var entered = new CountDownLatch(1);
        handler = exchange -> {
            entered.countDown();
            awaitRelease();
        };
        var result = new CompletableFuture<Boolean>();
        var client = plain(8192, 6000);
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                var error = assertThrows(LokiOperationException.class, () -> range(client));
                assertSafe(error, OPERATION_CANCELLED);
                result.complete(Thread.currentThread().isInterrupted());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            worker.interrupt();
            assertTrue(result.get(2, TimeUnit.SECONDS));
        } finally {
            worker.interrupt();
        }
    }

    @Test
    void rejectsMalformedJsonApiErrorsAndUnexpectedCompression() {
        var client = plain(8192, 3000);
        handler = exchange -> respond(exchange, 200, "<html>SECRET</html>", false);
        assertSafe(assertThrows(LokiOperationException.class, () -> range(client)), UPSTREAM_INVALID_RESPONSE);
        handler = exchange -> respond(exchange, 200, "{\"status\":\"error\",\"error\":\"bad query\"}", false);
        var queryError = assertThrows(LokiOperationException.class, () -> range(client));
        assertEquals(UPSTREAM_QUERY_ERROR, queryError.error().code());
        assertEquals("Loki rejected the query: bad query", queryError.error().message());
        handler = exchange -> {
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            respond(exchange, 200, EMPTY, false);
        };
        assertSafe(assertThrows(LokiOperationException.class, () -> range(client)), UPSTREAM_INVALID_RESPONSE);
    }

    @Test
    void invalidArgumentsAndConnectionNamesDoNotSendRequests() {
        var client = plain(8192, 3000);
        assertSafe(assertThrows(LokiOperationException.class, () -> client.queryInstant(null, "q", START)), CONNECTION_REQUIRED);
        assertSafe(assertThrows(LokiOperationException.class, () -> client.queryInstant("unknown", "q", START)), UNKNOWN_CONNECTION);
        assertSafe(assertThrows(LokiOperationException.class, () -> client.queryInstant("SECRET/", "q", START)), INVALID_CONNECTION);
        assertSafe(assertThrows(LokiOperationException.class, () -> client.queryInstant("local", " ", START)), INVALID_ARGUMENT);
        assertSafe(assertThrows(LokiOperationException.class, () -> client.queryInstant("local", "q", Instant.MAX)), INVALID_ARGUMENT);
        assertSafe(assertThrows(LokiOperationException.class, () -> client.labelValues("local", "../config", START, END, null)), INVALID_ARGUMENT);
        assertSafe(assertThrows(LokiOperationException.class, () -> client.series("local", List.of(), START, END)), INVALID_ARGUMENT);
        assertSafe(assertThrows(LokiOperationException.class, () -> client.labels("local", END, START, null)), INVALID_ARGUMENT);
        assertSafe(assertThrows(LokiOperationException.class, () -> client.queryRange("local", "q", START, END, 0, LokiHttpClient.Direction.FORWARD, null)), INVALID_ARGUMENT);
        assertSafe(assertThrows(LokiOperationException.class, () -> client.queryRange("local", "q", START, END, 1, null, null)), INVALID_ARGUMENT);
        assertSafe(assertThrows(LokiOperationException.class, () -> client.queryRange("local", "q", START, END, 1, LokiHttpClient.Direction.FORWARD, BigDecimal.ZERO)), INVALID_ARGUMENT);
        assertTrue(requests.isEmpty());
        client.close();
        assertSafe(assertThrows(LokiOperationException.class, () -> range(client)), OPERATION_CANCELLED);
    }

    @Test
    void connectionRefusalIsSafe() {
        var client = plain(8192, 3000);
        server.stop(0);
        assertSafe(assertThrows(LokiOperationException.class, () -> range(client)), UPSTREAM_CONNECTION_ERROR);
    }

    private void awaitRelease() {
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private List<String> parameters(Captured request, String key) {
        var values = new ArrayList<String>();
        for (String pair : request.uri().getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            if (URLDecoder.decode(parts[0], StandardCharsets.UTF_8).equals(key)) {
                values.add(URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private String parameter(Captured request, String key) {
        return parameters(request, key).stream().findFirst().orElse(null);
    }

    private void assertSafe(LokiOperationException error, ErrorCode code) {
        assertEquals(code, error.error().code());
        assertEquals(error.error(), Errors.from(error));
        assertNull(error.getCause());
        var stack = new StringWriter();
        error.printStackTrace(new PrintWriter(stack));
        for (String secret : List.of("SECRET", "secret-token", "secret-password", "tenant-a", "127.0.0.1")) {
            assertFalse(stack.toString().contains(secret), "Sensitive data in exception");
        }
    }

    private record Captured(String method, URI uri, String auth, String tenant, String accept) {
    }
}
