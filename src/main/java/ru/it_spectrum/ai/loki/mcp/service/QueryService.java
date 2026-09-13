package ru.it_spectrum.ai.loki.mcp.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.client.LokiResponses;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;
import ru.it_spectrum.ai.loki.mcp.model.QueryResults.*;
import static ru.it_spectrum.ai.loki.mcp.service.QueryTime.require;

@Service
public class QueryService {
    private final ConnectionRegistry registry;
    private final LokiHttpClient client;
    private final Clock clock;
    @Autowired
    public QueryService(ConnectionRegistry registry, LokiHttpClient client) { this(registry, client, Clock.systemUTC()); }
    public QueryService(ConnectionRegistry registry, LokiHttpClient client, Clock clock) {
        this.registry = registry; this.client = client; this.clock = clock;
    }

    public Logs logs(String connection, String query, String start, String end, String direction, Integer limit) {
        var definition = registry.require(connection);
        require(query != null && !query.isBlank());
        var window = QueryTime.range(start, end, clock.instant(), definition.timezone(), definition.limits().maxIntervalSeconds());
        int usedLimit = bounded(limit, definition.limits().maxEntries());
        String usedDirection = direction == null ? "backward" : direction;
        require(usedDirection.equals("forward") || usedDirection.equals("backward"));
        var response = client.queryRange(connection, query, window.start(), window.end(), usedLimit,
                usedDirection.equals("forward") ? LokiHttpClient.Direction.FORWARD : LokiHttpClient.Direction.BACKWARD, null);
        if (!(response.data() instanceof LokiResponses.Streams streams)) throw wrongType();
        var events = new ArrayList<Event>();
        for (var stream : streams.streams()) for (var entry : stream.entries()) {
            events.add(new Event(entry.timestampNanos(), stream.labels(), entry.line(), entry.structuredMetadata()));
        }
        Comparator<Event> order = Comparator.comparingLong(e -> Long.parseLong(e.timestampNanos()));
        if (usedDirection.equals("backward")) order = order.reversed();
        events.sort(order); // Stable for ties; never deduplicate or advance a boundary.
        int read = events.size();
        var shown = events.subList(0, Math.min(read, usedLimit));
        boolean reached = read >= usedLimit;
        var limitations = limitations(response);
        limitations.add("RESULT_LABELS_ARE_NOT_PROVEN_STREAM_SCOPE");
        limitations.add("LINES_ARE_QUERY_OUTPUT_ORIGINAL_CONTENT_NOT_GUARANTEED");
        if (reached) limitations.add("UPSTREAM_LIMIT_REACHED_MORE_MATCHES_UNKNOWN");
        if (read > usedLimit) limitations.add("LOCAL_ENTRY_LIMIT");
        return new Logs(connection, window.model(), usedDirection, usedLimit, read, shown.size(), streams.streams().size(),
                reached, read > usedLimit ? Completeness.PARTIAL : reached || !response.warnings().isEmpty()
                ? Completeness.UNKNOWN : Completeness.COMPLETE, "CURSORS_NOT_IMPLEMENTED", scanned(response), limitations, shown);
    }

    public Metrics metrics(String connection, String query, String mode, String start, String end,
                           String time, BigDecimal stepSeconds, Integer seriesLimit, Integer pointLimit) {
        var definition = registry.require(connection);
        require(query != null && !query.isBlank());
        require("instant".equals(mode) || "range".equals(mode));
        int maxSeries = bounded(seriesLimit, definition.limits().maxMetricSeries());
        int maxPoints = bounded(pointLimit, definition.limits().maxMetricPoints());
        var now = clock.instant();
        Window window;
        LokiResponses.QueryResponse response;
        if (mode.equals("instant")) {
            require(start == null && end == null && stepSeconds == null);
            var instant = QueryTime.parse(time, now, definition.timezone());
            window = new Window(QueryTime.nanos(instant), QueryTime.nanos(instant));
            response = client.queryInstant(connection, query, instant);
            if (!(response.data() instanceof LokiResponses.Vector)) throw wrongType();
        } else {
            require(time == null && stepSeconds != null && stepSeconds.compareTo(new BigDecimal("0.001")) >= 0);
            var range = QueryTime.range(start, end, now, definition.timezone(), definition.limits().maxIntervalSeconds());
            BigDecimal seconds = new BigDecimal(new java.math.BigInteger(QueryTime.nanos(range.end()))
                    .subtract(new java.math.BigInteger(QueryTime.nanos(range.start()))), 9);
            // Bound evaluation instants per series before dispatch, without rewriting the user's step.
            require(seconds.divideToIntegralValue(stepSeconds).add(BigDecimal.ONE).compareTo(BigDecimal.valueOf(maxPoints)) <= 0);
            window = range.model();
            response = client.queryRange(connection, query, range.start(), range.end(), definition.limits().maxEntries(),
                    LokiHttpClient.Direction.FORWARD, stepSeconds);
            if (!(response.data() instanceof LokiResponses.Matrix)) throw wrongType();
        }
        List<Series> readSeries;
        if (response.data() instanceof LokiResponses.Vector vector) {
            readSeries = vector.samples().stream().map(s -> new Series(s.labels(), List.of(sample(s.sample())))).toList();
        } else {
            readSeries = ((LokiResponses.Matrix) response.data()).series().stream()
                    .map(s -> new Series(s.labels(), s.samples().stream().map(QueryService::sample).toList())).toList();
        }
        int readPoints = readSeries.stream().mapToInt(s -> s.samples().size()).sum();
        var shown = new ArrayList<Series>();
        int points = 0;
        for (var series : readSeries) {
            if (shown.size() >= maxSeries || points >= maxPoints) break;
            var samples = series.samples().subList(0, Math.min(series.samples().size(), maxPoints - points));
            shown.add(new Series(series.labels(), samples));
            points += samples.size();
        }
        boolean trimmed = shown.size() != readSeries.size() || points != readPoints;
        var limitations = limitations(response);
        if (trimmed) limitations.add("LOCAL_SERIES_OR_POINT_LIMIT");
        limitations.add("WINDOW_IS_EVALUATION_TIME_LOGQL_LOOKBACK_MAY_EXTEND_BEFORE_START");
        return new Metrics(connection, mode, window, stepSeconds, maxSeries, maxPoints, readSeries.size(), readPoints,
                shown.size(), points, trimmed ? Completeness.PARTIAL : response.warnings().isEmpty()
                ? Completeness.COMPLETE : Completeness.UNKNOWN, "CURSORS_NOT_IMPLEMENTED", scanned(response), limitations, shown);
    }
    private static int bounded(Integer requested, int maximum) {
        int value = requested == null ? maximum : requested;
        require(value > 0 && value <= maximum);
        return value;
    }
    private static Sample sample(LokiResponses.MetricSample sample) { return new Sample(sample.timestampSeconds(), sample.value()); }
    private static Long scanned(LokiResponses.QueryResponse response) { return response.stats() == null ? null : response.stats().totalLinesProcessed(); }
    private static ArrayList<String> limitations(LokiResponses.QueryResponse response) {
        var result = new ArrayList<String>();
        // Upstream warning text may contain secrets. Expose its presence, never raw diagnostics.
        if (!response.warnings().isEmpty()) result.add("UPSTREAM_WARNINGS_PRESENT_DETAILS_WITHHELD");
        return result;
    }
    private static LokiOperationException wrongType() {
        return Errors.failure(ErrorCode.UPSTREAM_INVALID_RESPONSE, "Unexpected result type for this tool and mode. Check the LogQL query.");
    }
}
