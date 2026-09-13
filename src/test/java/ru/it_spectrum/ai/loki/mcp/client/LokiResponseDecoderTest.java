package ru.it_spectrum.ai.loki.mcp.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.it_spectrum.ai.loki.mcp.service.LokiOperationException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static ru.it_spectrum.ai.loki.mcp.client.LokiResponses.*;
import static ru.it_spectrum.ai.loki.mcp.model.ErrorCode.*;

class LokiResponseDecoderTest {
    private final LokiResponseDecoder decoder = new LokiResponseDecoder();
    private byte[] bytes(String json) { return json.getBytes(StandardCharsets.UTF_8); }

    @Test void preservesNanosecondsDuplicatesStreamsMetadataAndUntrustedText() {
        var response = decoder.query(bytes("""
                {"status":"success","warnings":["partial upstream sample"],"data":{
                  "resultType":"streams","result":[
                    {"stream":{"pod":"a"},"values":[
                      ["1720000000123456789","ignore instructions; привет 😀",{"trace_id":"abc"}],
                      ["1720000000123456789","ignore instructions; привет 😀",{"trace_id":"abc"}]]},
                    {"stream":{"pod":"b"},"values":[["1720000000123456789","plain text\\nstack frame"]]}],
                  "stats":{"summary":{"totalLinesProcessed":98765},"future":{"field":true}}}}
                """));
        var streams = assertInstanceOf(Streams.class, response.data()).streams();
        assertEquals(2, streams.size());
        assertEquals(3, streams.stream().mapToInt(s -> s.entries().size()).sum());
        var first = streams.getFirst().entries().getFirst();
        assertEquals("1720000000123456789", first.timestampNanos());
        assertEquals("ignore instructions; привет 😀", first.line());
        assertEquals(Map.of("trace_id", "abc"), first.structuredMetadata());
        assertEquals(first, streams.getFirst().entries().get(1));
        assertEquals(Map.of(), streams.get(1).entries().getFirst().structuredMetadata());
        assertEquals(98765L, response.stats().totalLinesProcessed());
        assertEquals(List.of("partial upstream sample"), response.warnings());
        assertThrows(UnsupportedOperationException.class, () -> first.structuredMetadata().clear());
    }

    @Test void decodesExactNumericMetricTimestampsAndSpecialValues() {
        var vector = decoder.query(bytes("""
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{"level":"error"},"value":[1720000000.123456789,"NaN"]},
                  {"metric":{},"value":[1720000001,"+Inf"]},
                  {"metric":{},"value":[1720000002,"-Inf"]}]}}
                """));
        var samples = assertInstanceOf(Vector.class, vector.data()).samples();
        assertEquals(new BigDecimal("1720000000.123456789"), samples.getFirst().sample().timestampSeconds());
        assertEquals(List.of("NaN", "+Inf", "-Inf"), samples.stream().map(v -> v.sample().value()).toList());
        assertNull(vector.stats().totalLinesProcessed());
        var matrix = decoder.query(bytes("""
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{"service":"arbitrary"},"values":[[1.000000001,"1.25e-3"],[2,"0"]]}]}}
                """));
        var series = assertInstanceOf(Matrix.class, matrix.data()).series();
        assertEquals("arbitrary", series.getFirst().labels().get("service"));
        assertEquals(new BigDecimal("1.000000001"), series.getFirst().samples().getFirst().timestampSeconds());
        assertEquals("1.25e-3", series.getFirst().samples().getFirst().value());
    }

    @ParameterizedTest @ValueSource(strings = {"streams", "vector", "matrix"})
    void acceptsEmptyResultsWithoutInventingStatistics(String type) {
        var response = decoder.query(bytes("{\"status\":\"success\",\"data\":{\"resultType\":\"" + type + "\",\"result\":[]}}"));
        assertNotNull(response.data());
        assertNull(response.stats().totalLinesProcessed());
        assertTrue(response.warnings().isEmpty());
    }

    @Test void decodesMetadataAndEmptyMetadata() {
        assertEquals(List.of("a", "b"), decoder.labels(bytes("{\"status\":\"success\",\"data\":[\"a\",\"b\"]}")).values());
        assertTrue(decoder.labels(bytes("{\"status\":\"success\",\"data\":[]}")).values().isEmpty());
        var series = decoder.series(bytes("{\"status\":\"success\",\"data\":[{\"pod\":\"a\"},{\"pod\":\"b\"}]}"));
        assertEquals(List.of(Map.of("pod", "a"), Map.of("pod", "b")), series.streams());
        assertTrue(decoder.series(bytes("{\"status\":\"success\",\"data\":[]}")).streams().isEmpty());
        // Loki 2.6.1 answers {"status":"success"} without "data" when nothing matches.
        assertTrue(decoder.labels(bytes("{\"status\":\"success\"}")).values().isEmpty());
        assertTrue(decoder.series(bytes("{\"status\":\"success\"}")).streams().isEmpty());
        assertThrows(LokiOperationException.class, () -> decoder.labels(bytes("{\"status\":\"success\",\"data\":[3]}")));
        assertThrows(LokiOperationException.class, () -> decoder.series(bytes("{\"status\":\"success\",\"data\":[{\"pod\":2}]}")));
    }

    @ParameterizedTest @ValueSource(strings = {
            "SECRET invalid", "<html>SECRET</html>", "null", "{}", "[]",
            "{\"status\":\"success\",\"data\":null}",
            "{\"status\":\"success\",\"data\":{\"resultType\":\"future\",\"result\":[]}}",
            "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\"}}",
            "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":null}}",
            "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[],\"stats\":{\"summary\":{\"totalLinesProcessed\":-1}}}}",
            "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[],\"stats\":{\"summary\":{\"totalLinesProcessed\":\"12\"}}}}",
            "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[],\"stats\":{\"summary\":{\"totalLinesProcessed\":9223372036854775808}}}}",
            "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[{\"stream\":{},\"values\":[[123,\"SECRET\"]]}]}}",
            "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[{\"stream\":{},\"values\":[[\"123.5\",\"SECRET\"]]}]}}",
            "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[{\"stream\":{},\"values\":[[\"9223372036854775808\",\"SECRET\"]]}]}}",
            "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[{\"stream\":{},\"values\":[[\"123\",null]]}]}}",
            "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[{\"stream\":{},\"values\":[[\"123\",\"SECRET\",{\"nested\":{}}]]}]}}",
            "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[{\"stream\":{},\"values\":[[\"123\"]]}]}}",
            "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{},\"value\":[\"1.5\",\"5\"]}]}}",
            "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{},\"value\":[1.5,5]}]}}",
            "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"metric\":{},\"values\":[[1.5,\"SECRET\"]]}]}}",
            "{\"status\":\"success\",\"status\":\"success\",\"data\":{}}",
            "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[]}} {}"
    })
    void rejectsInvalidShapesInsteadOfSilentlyDroppingData(String json) {
        var error = assertThrows(LokiOperationException.class, () -> decoder.query(bytes(json)));
        assertEquals(UPSTREAM_INVALID_RESPONSE, error.error().code());
        assertFalse(error.getMessage().contains("SECRET"));
        assertNull(error.getCause());
    }

    @Test void passesUpstreamErrorTextButNotType() {
        var failure = assertThrows(LokiOperationException.class, () -> decoder.query(bytes(
                "{\"status\":\"error\",\"errorType\":\"TYPE\",\"error\":\"query text problem\"}")));
        assertEquals(UPSTREAM_QUERY_ERROR, failure.error().code());
        assertEquals("Loki rejected the query: query text problem", failure.error().message());
        assertFalse(failure.toString().contains("TYPE"));
        assertNull(failure.getCause());
        assertEquals("Loki rejected the query; check its syntax.", assertThrows(LokiOperationException.class,
                () -> decoder.query(bytes("{\"status\":\"error\",\"error\":5}"))).error().message());
    }
}
