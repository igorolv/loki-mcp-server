package ru.it_spectrum.ai.loki.mcp.client;

import ru.it_spectrum.ai.loki.mcp.service.LokiOperationException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ru.it_spectrum.ai.loki.mcp.client.LokiResponses.*;
import static ru.it_spectrum.ai.loki.mcp.model.ErrorCode.UPSTREAM_INVALID_RESPONSE;
import static ru.it_spectrum.ai.loki.mcp.model.ErrorCode.UPSTREAM_QUERY_ERROR;

/**
 * Strict about known data shapes; unknown object fields remain forward compatible.
 */
final class LokiResponseDecoder {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    QueryResponse query(byte[] bytes) {
        JsonNode root = root(bytes);
        try {
            JsonNode data = object(root.path("data"));
            JsonNode result = array(data.path("result"));
            QueryData decoded = switch (string(data.path("resultType"))) {
                case "streams" -> streams(result);
                case "vector" -> vector(result);
                case "matrix" -> matrix(result);
                default -> throw TransportErrors.error(UPSTREAM_INVALID_RESPONSE);
            };
            Long processed = null;
            if (data.has("stats")) {
                JsonNode stats = object(data.path("stats"));
                if (stats.has("summary")) {
                    JsonNode summary = object(stats.path("summary"));
                    if (summary.has("totalLinesProcessed")) {
                        JsonNode count = summary.path("totalLinesProcessed");
                        check(count.isIntegralNumber() && count.canConvertToLong() && count.longValue() >= 0);
                        processed = count.longValue();
                    }
                }
            }
            return new QueryResponse(decoded, new QueryStats(processed), warnings(root));
        } catch (Exception ignored) {
            throw TransportErrors.error(UPSTREAM_INVALID_RESPONSE);
        }
    }

    // Loki 2.6.1 omits "data" entirely from /labels, /label/{name}/values and /series when nothing matches.
    LabelResponse labels(byte[] bytes) {
        JsonNode root = root(bytes);
        return new LabelResponse(root.has("data") ? strings(root.path("data")) : List.of(), warnings(root));
    }

    SeriesResponse series(byte[] bytes) {
        JsonNode root = root(bytes);
        var streams = new ArrayList<Map<String, String>>();
        if (root.has("data")) for (JsonNode stream : array(root.path("data"))) streams.add(labelsMap(stream));
        return new SeriesResponse(streams, warnings(root));
    }

    private JsonNode root(byte[] bytes) {
        try {
            JsonNode root = object(MAPPER.readTree(bytes));
            String status = string(root.path("status"));
            if ("error".equals(status)) {
                // The error text explains the model's own query; errorType is not needed.
                throw TransportErrors.queryError(UPSTREAM_QUERY_ERROR, root.path("error").isString() ? root.path("error").stringValue() : null);
            }
            check("success".equals(status));
            return root;
        } catch (LokiOperationException safe) {
            throw safe;
        } catch (Exception ignored) {
            throw TransportErrors.error(UPSTREAM_INVALID_RESPONSE);
        }
    }

    private Streams streams(JsonNode result) {
        var streams = new ArrayList<LogStream>();
        for (JsonNode stream : result) {
            var entries = new ArrayList<LogEntry>();
            for (JsonNode row : array(stream.path("values"))) {
                array(row);
                check(row.size() == 2 || row.size() == 3);
                String timestamp = string(row.get(0));
                check(timestamp.matches("-?[0-9]+"));
                Long.parseLong(timestamp); // Loki log timestamps are signed int64 nanoseconds.
                entries.add(new LogEntry(timestamp, string(row.get(1)),
                        row.size() == 3 ? labelsMap(row.get(2)) : Map.of()));
            }
            streams.add(new LogStream(labelsMap(stream.path("stream")), entries));
        }
        return new Streams(streams);
    }

    private Vector vector(JsonNode result) {
        var samples = new ArrayList<VectorSample>();
        for (JsonNode series : result) {
            samples.add(new VectorSample(labelsMap(series.path("metric")), sample(series.path("value"))));
        }
        return new Vector(samples);
    }

    private Matrix matrix(JsonNode result) {
        var series = new ArrayList<MetricSeries>();
        for (JsonNode item : result) {
            var samples = new ArrayList<MetricSample>();
            for (JsonNode row : array(item.path("values"))) samples.add(sample(row));
            series.add(new MetricSeries(labelsMap(item.path("metric")), samples));
        }
        return new Matrix(series);
    }

    private MetricSample sample(JsonNode row) {
        array(row);
        check(row.size() == 2 && row.get(0).isNumber());
        BigDecimal timestamp = row.get(0).decimalValue();
        String value = string(row.get(1));
        if (!List.of("NaN", "+Inf", "-Inf").contains(value)) new BigDecimal(value);
        return new MetricSample(timestamp, value);
    }

    private Map<String, String> labelsMap(JsonNode node) {
        object(node);
        var map = new LinkedHashMap<String, String>();
        node.properties().forEach(entry -> map.put(entry.getKey(), string(entry.getValue())));
        return map;
    }

    private List<String> warnings(JsonNode root) {
        return root.has("warnings") ? strings(root.path("warnings")) : List.of();
    }

    private List<String> strings(JsonNode node) {
        var values = new ArrayList<String>();
        for (JsonNode item : array(node)) values.add(string(item));
        return values;
    }

    private JsonNode object(JsonNode node) {
        check(node != null && node.isObject());
        return node;
    }

    private JsonNode array(JsonNode node) {
        check(node != null && node.isArray());
        return node;
    }

    private String string(JsonNode node) {
        check(node != null && node.isString());
        return node.stringValue();
    }

    private void check(boolean condition) {
        if (!condition) throw TransportErrors.error(UPSTREAM_INVALID_RESPONSE);
    }
}
