package ru.it_spectrum.ai.loki.mcp.client;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionDefinition;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.service.LokiOperationException;
import static ru.it_spectrum.ai.loki.mcp.client.LokiResponses.*;
import static ru.it_spectrum.ai.loki.mcp.model.ErrorCode.*;

/** Only known GET endpoints are exposed. Each call requires an explicit registry name. */
public final class LokiHttpClient implements AutoCloseable {
    public enum Direction { FORWARD, BACKWARD }
    private final ConnectionRegistry registry;
    private final LokiResponseDecoder decoder = new LokiResponseDecoder();
    private final Map<String, HttpClient> clients = new HashMap<>();
    private boolean closed;

    public LokiHttpClient(ConnectionRegistry registry) { this.registry = registry; }

    public QueryResponse queryRange(String connection, String query, Instant start, Instant end,
                                    int limit, Direction direction, BigDecimal stepSeconds) {
        var definition = registry.require(connection);
        var params = window(start, end);
        require(query != null && !query.isBlank() && limit > 0 && direction != null);
        require(stepSeconds == null || stepSeconds.signum() > 0);
        params.add(new Param("query", query));
        params.add(new Param("limit", Integer.toString(limit)));
        params.add(new Param("direction", direction == Direction.FORWARD ? "forward" : "backward"));
        if (stepSeconds != null) params.add(new Param("step", stepSeconds.toPlainString()));
        return decoder.query(get(definition, "/loki/api/v1/query_range", params));
    }

    public QueryResponse queryInstant(String connection, String query, Instant time) {
        var definition = registry.require(connection);
        require(query != null && !query.isBlank());
        return decoder.query(get(definition, "/loki/api/v1/query",
                List.of(new Param("query", query), new Param("time", nanos(time)))));
    }

    public LabelResponse labels(String connection, Instant start, Instant end, String selector) {
        var definition = registry.require(connection);
        return decoder.labels(get(definition, "/loki/api/v1/labels", metadataParams(start, end, selector)));
    }

    public LabelResponse labelValues(String connection, String label, Instant start, Instant end, String selector) {
        var definition = registry.require(connection);
        require(label != null && label.matches("[a-zA-Z_][a-zA-Z0-9_]*"));
        return decoder.labels(get(definition, "/loki/api/v1/label/" + label + "/values",
                metadataParams(start, end, selector)));
    }

    public SeriesResponse series(String connection, List<String> selectors, Instant start, Instant end) {
        var definition = registry.require(connection);
        var params = window(start, end);
        require(selectors != null && !selectors.isEmpty());
        for (String selector : selectors) {
            require(selector != null && !selector.isBlank());
            params.add(new Param("match[]", selector));
        }
        return decoder.series(get(definition, "/loki/api/v1/series", params));
    }

    private byte[] get(ConnectionDefinition connection, String path, List<Param> params) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        var subscriber = new AtomicReference<LimitedBodySubscriber>();
        try {
            String base = connection.url().toASCIIString().replaceAll("/+$", "");
            String query = params.stream().map(p -> encode(p.name()) + "=" + encode(p.value()))
                    .collect(java.util.stream.Collectors.joining("&"));
            var request = HttpRequest.newBuilder(URI.create(base + path + "?" + query))
                    .timeout(Duration.ofMillis(connection.limits().requestTimeoutMs()))
                    .header("Accept", "application/json").header("Accept-Encoding", "identity").GET();
            var auth = connection.auth();
            switch (auth.type()) {
                case BASIC -> request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (auth.username() + ":" + auth.password()).getBytes(StandardCharsets.UTF_8)));
                case BEARER -> request.header("Authorization", "Bearer " + auth.token());
                case NONE -> { }
            }
            if (connection.tenant() != null) request.header("X-Scope-OrgID", connection.tenant());
            pending = client(connection).sendAsync(request.build(), info -> {
                var body = new LimitedBodySubscriber(connection.limits().maxHttpResponseBytes());
                subscriber.set(body);
                if (info.statusCode() != 200 && info.statusCode() != 400) {
                    body.fail(TransportErrors.http(info.statusCode()));
                } else if (!info.headers().firstValue("Content-Encoding").orElse("identity").equalsIgnoreCase("identity")) {
                    body.fail(TransportErrors.error(UPSTREAM_INVALID_RESPONSE));
                } else if (info.headers().firstValueAsLong("Content-Length").orElse(0)
                        > connection.limits().maxHttpResponseBytes()) {
                    body.fail(TransportErrors.error(UPSTREAM_RESPONSE_TOO_LARGE));
                }
                return body;
            });
            // Unlike an InputStream body handler, completion means the entire bounded body arrived.
            var response = pending.get(connection.limits().requestTimeoutMs(), TimeUnit.MILLISECONDS);
            // A 400 body is Loki's explanation of what is wrong with the model's own query; it is passed on.
            if (response.statusCode() == 400) throw TransportErrors.badRequest(new String(response.body(), StandardCharsets.UTF_8));
            return response.body();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            throw TransportErrors.error(OPERATION_CANCELLED);
        } catch (TimeoutException ignored) {
            throw TransportErrors.error(UPSTREAM_TIMEOUT);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            while (cause != null) {
                if (cause instanceof LokiOperationException safe) throw safe;
                if (cause instanceof HttpTimeoutException) throw TransportErrors.error(UPSTREAM_TIMEOUT);
                cause = cause.getCause();
            }
            throw TransportErrors.error(UPSTREAM_CONNECTION_ERROR);
        } catch (LokiOperationException safe) {
            throw safe;
        } catch (Exception ignored) {
            throw TransportErrors.error(UPSTREAM_CONNECTION_ERROR);
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            var body = subscriber.get();
            if (body != null && !body.getBody().toCompletableFuture().isDone()) {
                body.fail(TransportErrors.error(OPERATION_CANCELLED));
            }
        }
    }

    private synchronized HttpClient client(ConnectionDefinition connection) {
        if (closed) throw TransportErrors.error(OPERATION_CANCELLED);
        return clients.computeIfAbsent(connection.name(), ignored -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connection.limits().connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    @Override public synchronized void close() {
        closed = true;
        clients.values().forEach(HttpClient::shutdownNow);
        clients.clear();
    }

    private List<Param> metadataParams(Instant start, Instant end, String selector) {
        var params = window(start, end);
        if (selector != null) {
            require(!selector.isBlank());
            params.add(new Param("query", selector));
        }
        return params;
    }
    private List<Param> window(Instant start, Instant end) {
        require(start != null && end != null && start.isBefore(end));
        return new ArrayList<>(List.of(new Param("start", nanos(start)), new Param("end", nanos(end))));
    }
    private String nanos(Instant time) {
        require(time != null);
        try {
            return Long.toString(BigInteger.valueOf(time.getEpochSecond()).multiply(BigInteger.valueOf(1_000_000_000))
                    .add(BigInteger.valueOf(time.getNano())).longValueExact());
        } catch (ArithmeticException ignored) {
            throw TransportErrors.error(INVALID_ARGUMENT);
        }
    }
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private void require(boolean valid) { if (!valid) throw TransportErrors.error(INVALID_ARGUMENT); }
    private record Param(String name, String value) {}
}
