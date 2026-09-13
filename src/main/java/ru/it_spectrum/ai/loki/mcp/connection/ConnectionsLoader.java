package ru.it_spectrum.ai.loki.mcp.connection;

import ru.it_spectrum.ai.loki.mcp.service.Errors;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

public final class ConnectionsLoader {
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private ConnectionsLoader() {
    }

    public static List<ConnectionDefinition> load(Path path, UnaryOperator<String> environment) {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw Errors.configuration();
            FileConfig config = MAPPER.readValue(bytes, FileConfig.class);
            if (config == null || config.connections() == null || config.connections().isEmpty()) {
                throw Errors.configuration();
            }
            var definitions = new ArrayList<ConnectionDefinition>();
            for (var pair : config.connections().entrySet()) {
                Entry e = pair.getValue();
                if (e == null) throw Errors.configuration();
                Auth a = e.auth();
                ConnectionAuth auth = a == null ? ConnectionAuth.NONE : new ConnectionAuth(a.type(),
                        resolve(a.username(), environment), resolve(a.password(), environment), resolve(a.token(), environment));
                Limits l = e.limits() == null ? new Limits(null, null, null, null, null, null, null, null) : e.limits();
                ConnectionLimits d = ConnectionLimits.DEFAULTS;
                var limits = new ConnectionLimits(or(l.connectTimeoutMs(), d.connectTimeoutMs()),
                        or(l.requestTimeoutMs(), d.requestTimeoutMs()), or(l.maxHttpResponseBytes(), d.maxHttpResponseBytes()),
                        or(l.maxResponseBytes(), d.maxResponseBytes()), or(l.maxEntries(), d.maxEntries()),
                        l.maxIntervalSeconds() == null ? d.maxIntervalSeconds() : l.maxIntervalSeconds(),
                        or(l.maxMetricSeries(), d.maxMetricSeries()), or(l.maxMetricPoints(), d.maxMetricPoints()));
                definitions.add(new ConnectionDefinition(pair.getKey(), e.description(), e.hint(),
                        URI.create(resolve(e.url(), environment)), auth, resolve(e.tenant(), environment),
                        ZoneId.of(e.timezone() == null ? "UTC" : e.timezone()), limits,
                        e.serviceLabels() == null ? ConnectionDefinition.DEFAULT_SERVICE_LABELS : e.serviceLabels()));
            }
            return List.copyOf(definitions);
        } catch (Exception ignored) {
            // Jackson, URI and filesystem errors can include source values. Do not retain their cause.
            throw Errors.configuration();
        }
    }

    private static int or(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static String resolve(String value, UnaryOperator<String> environment) {
        if (value == null) return null;
        var matcher = PLACEHOLDER.matcher(value);
        StringBuilder result = new StringBuilder();
        int end = 0;
        while (matcher.find()) {
            String replacement = environment.apply(matcher.group(1));
            if (replacement == null) throw Errors.configuration();
            result.append(value, end, matcher.start()).append(replacement);
            end = matcher.end();
        }
        result.append(value, end, value.length());
        if (result.indexOf("${") >= 0) throw Errors.configuration();
        return result.toString();
    }

    private record FileConfig(Map<String, Entry> connections) {
    }

    private record Entry(String description, String hint, String url, Auth auth, String tenant, String timezone,
                         Limits limits, List<String> serviceLabels) {
    }

    private record Auth(ConnectionAuth.Type type, String username, String password, String token) {
    }

    private record Limits(Integer connectTimeoutMs, Integer requestTimeoutMs, Integer maxHttpResponseBytes,
                          Integer maxResponseBytes, Integer maxEntries, Long maxIntervalSeconds,
                          Integer maxMetricSeries, Integer maxMetricPoints) {
    }
}
