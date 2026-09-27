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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

public final class ConnectionsLoader {
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_PATTERN_CHARS = 1000;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private ConnectionsLoader() {
    }

    public static Config loadConfig(Path path, UnaryOperator<String> environment) {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw Errors.configuration();
            FileConfig config = MAPPER.readValue(bytes, FileConfig.class);
            if (config == null || config.connections() == null || config.connections().isEmpty()) {
                throw Errors.configuration();
            }
            var definitions = new ArrayList<ConnectionDefinition>();
            var loaded = new HashMap<Path, LineFormats>();
            for (var pair : config.connections().entrySet()) {
                Entry entry = pair.getValue();
                if (entry == null) throw Errors.configuration();
                Auth a = entry.auth();
                ConnectionAuth auth = a == null ? ConnectionAuth.NONE : new ConnectionAuth(a.type(),
                        resolve(a.username(), environment), resolve(a.password(), environment), resolve(a.token(), environment));
                LineFormats formats = entry.formatFile() == null ? new LineFormats(List.of(), null)
                        : loaded.computeIfAbsent(resolvePath(path, resolve(entry.formatFile(), environment)),
                        ConnectionsLoader::loadFormatFile);
                Limits l = entry.limits() == null ? new Limits(null, null, null, null, null, null, null, null,
                        null, null, null, null, null) : entry.limits();
                ConnectionLimits d = ConnectionLimits.DEFAULTS;
                var limits = new ConnectionLimits(or(l.connectTimeoutMs(), d.connectTimeoutMs()),
                        or(l.requestTimeoutMs(), d.requestTimeoutMs()), or(l.maxHttpResponseBytes(), d.maxHttpResponseBytes()),
                        or(l.maxResponseBytes(), d.maxResponseBytes()), or(l.maxEntries(), d.maxEntries()),
                        l.maxIntervalSeconds() == null ? d.maxIntervalSeconds() : l.maxIntervalSeconds(),
                        or(l.maxExportLines(), d.maxExportLines()),
                        l.maxExportBytes() == null ? d.maxExportBytes() : l.maxExportBytes(),
                        l.maxCountIntervalSeconds() == null ? d.maxCountIntervalSeconds() : l.maxCountIntervalSeconds(),
                        l.maxTimeCountIntervalSeconds() == null ? d.maxTimeCountIntervalSeconds() : l.maxTimeCountIntervalSeconds(),
                        l.maxDiscoveryIntervalSeconds() == null ? d.maxDiscoveryIntervalSeconds() : l.maxDiscoveryIntervalSeconds(),
                        l.maxSeriesIntervalSeconds() == null ? d.maxSeriesIntervalSeconds() : l.maxSeriesIntervalSeconds(),
                        or(l.maxExportDurationMs(), d.maxExportDurationMs()));
                definitions.add(new ConnectionDefinition(pair.getKey(), entry.description(), entry.hint(),
                        URI.create(resolve(entry.url(), environment)), auth, resolve(entry.tenant(), environment),
                        ZoneId.of(entry.timezone() == null ? "UTC" : entry.timezone()), limits,
                        entry.serviceLabels() == null ? ConnectionDefinition.DEFAULT_SERVICE_LABELS : entry.serviceLabels(),
                        formats.formats(), formats.framePattern()));
            }
            return new Config(List.copyOf(definitions), exportRoots(path, config.exportRoots(), environment));
        } catch (Exception ignored) {
            // Parser and filesystem errors can quote credentials or URLs; keep them out of diagnostics.
            throw Errors.configuration();
        }
    }

    public static List<LineFormat> loadFormats(Path path) {
        return loadFormatFile(path).formats();
    }

    private static LineFormats loadFormatFile(Path path) {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw Errors.configuration();
            FormatConfig file = MAPPER.readValue(bytes, FormatConfig.class);
            if (file == null) throw Errors.configuration();
            var formats = new ArrayList<LineFormat>();
            if (file.formats() != null) {
                for (Format entry : file.formats()) {
                    if (entry == null) throw Errors.configuration();
                    formats.add(new LineFormat(entry.id(), compile(entry.pattern())));
                }
            }
            return new LineFormats(List.copyOf(formats),
                    file.framePattern() == null ? null : compile(file.framePattern()));
        } catch (Exception ignored) {
            throw Errors.configuration();
        }
    }

    private static Pattern compile(String regex) {
        if (regex == null || regex.isBlank() || regex.length() > MAX_PATTERN_CHARS) throw Errors.configuration();
        try {
            return Pattern.compile(regex);
        } catch (RuntimeException ignored) {
            throw Errors.configuration();
        }
    }

    private static List<Path> exportRoots(Path connections, List<String> roots, UnaryOperator<String> environment) {
        if (roots == null) return List.of();
        if (roots.isEmpty() || roots.size() > ExportRoots.MAX_ROOTS) throw Errors.configuration();
        var paths = new ArrayList<Path>();
        for (String root : roots) {
            if (root == null || root.isBlank()) throw Errors.configuration();
            paths.add(resolvePath(connections, resolve(root, environment)));
        }
        return List.copyOf(paths);
    }

    private static Path resolvePath(Path connections, String value) {
        Path path = Path.of(value);
        if (path.isAbsolute()) return path.normalize();
        Path directory = connections.toAbsolutePath().getParent();
        return (directory == null ? path : directory.resolve(path)).normalize();
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

    public record Config(List<ConnectionDefinition> connections, List<Path> exportRoots) {
    }

    private record FileConfig(Map<String, Entry> connections, List<String> exportRoots) {
    }

    private record Entry(String description, String hint, String url, Auth auth, String tenant, String timezone,
                         Limits limits, List<String> serviceLabels, String formatFile) {
    }

    private record LineFormats(List<LineFormat> formats, Pattern framePattern) {
    }

    private record FormatConfig(List<Format> formats, String framePattern) {
    }

    private record Format(String id, String pattern) {
    }

    private record Auth(ConnectionAuth.Type type, String username, String password, String token) {
    }

    private record Limits(Integer connectTimeoutMs, Integer requestTimeoutMs, Integer maxHttpResponseBytes,
                          Integer maxResponseBytes, Integer maxEntries, Long maxIntervalSeconds,
                          Integer maxExportLines, Long maxExportBytes,
                           Long maxCountIntervalSeconds, Long maxTimeCountIntervalSeconds,
                           Long maxDiscoveryIntervalSeconds, Long maxSeriesIntervalSeconds,
                           Integer maxExportDurationMs) {
    }
}
