package ru.it_spectrum.ai.loki.mcp.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Transport models, deliberately separate from the public MCP contract.
 */
public final class LokiResponses {
    private LokiResponses() {
    }

    public sealed interface QueryData permits Streams, Vector, Matrix {
    }

    public record QueryResponse(QueryData data) {
    }

    public record Streams(List<LogStream> streams) implements QueryData {
        public Streams {
            streams = List.copyOf(streams);
        }
    }

    public record Vector(List<VectorSample> samples) implements QueryData {
        public Vector {
            samples = List.copyOf(samples);
        }
    }

    public record Matrix(List<MetricSeries> series) implements QueryData {
        public Matrix {
            series = List.copyOf(series);
        }
    }

    public record LogStream(Map<String, String> labels, List<LogEntry> entries) {
        public LogStream {
            labels = Map.copyOf(labels);
            entries = List.copyOf(entries);
        }
    }

    public record LogEntry(String timestampNanos, String line, Map<String, String> structuredMetadata) {
        public LogEntry {
            structuredMetadata = Map.copyOf(structuredMetadata);
        }
    }

    /**
     * Numeric seconds are parsed exactly; values remain strings, including NaN and +/-Inf.
     */
    public record MetricSample(BigDecimal timestampSeconds, String value) {
    }

    public record VectorSample(Map<String, String> labels, MetricSample sample) {
        public VectorSample {
            labels = Map.copyOf(labels);
        }
    }

    public record MetricSeries(Map<String, String> labels, List<MetricSample> samples) {
        public MetricSeries {
            labels = Map.copyOf(labels);
            samples = List.copyOf(samples);
        }
    }

    public record LabelResponse(List<String> values) {
        public LabelResponse {
            values = List.copyOf(values);
        }
    }

    public record SeriesResponse(List<Map<String, String>> labelSets) {
        public SeriesResponse {
            labelSets = labelSets.stream().map(Map::copyOf).toList();
        }
    }

}
