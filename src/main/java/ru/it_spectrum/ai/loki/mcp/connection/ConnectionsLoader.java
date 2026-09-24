package ru.it_spectrum.ai.loki.mcp.connection;

import ru.it_spectrum.ai.loki.mcp.service.Errors;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
        return loadConfig(path, environment).connections();
    }

    /**
     * The connections and the export directories of the file; {@code exportRoots} is empty when the file names none.
     */
    public static Config loadConfig(Path path, UnaryOperator<String> environment) {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw Errors.configuration();
            FileConfig config = MAPPER.readValue(bytes, FileConfig.class);
            if (config == null || config.connections() == null || config.connections().isEmpty()) {
                throw Errors.configuration();
            }
            var definitions = new ArrayList<ConnectionDefinition>();
            var rulesByFile = new HashMap<Path, Catalogue>();
            for (var pair : config.connections().entrySet()) {
                Entry e = pair.getValue();
                if (e == null) throw Errors.configuration();
                Auth a = e.auth();
                ConnectionAuth auth = a == null ? ConnectionAuth.NONE : new ConnectionAuth(a.type(),
                        resolve(a.username(), environment), resolve(a.password(), environment), resolve(a.token(), environment));
                var catalogue = catalogue(path, e.rulesFile(), environment, rulesByFile);
                Limits l = e.limits() == null ? new Limits(null, null, null, null, null, null, null, null) : e.limits();
                ConnectionLimits d = ConnectionLimits.DEFAULTS;
                var limits = new ConnectionLimits(or(l.connectTimeoutMs(), d.connectTimeoutMs()),
                        or(l.requestTimeoutMs(), d.requestTimeoutMs()), or(l.maxHttpResponseBytes(), d.maxHttpResponseBytes()),
                        or(l.maxResponseBytes(), d.maxResponseBytes()), or(l.maxEntries(), d.maxEntries()),
                        l.maxIntervalSeconds() == null ? d.maxIntervalSeconds() : l.maxIntervalSeconds(),
                        or(l.maxExportLines(), d.maxExportLines()),
                        l.maxExportBytes() == null ? d.maxExportBytes() : l.maxExportBytes());
                definitions.add(new ConnectionDefinition(pair.getKey(), e.description(), e.hint(),
                        URI.create(resolve(e.url(), environment)), auth, resolve(e.tenant(), environment),
                        ZoneId.of(e.timezone() == null ? "UTC" : e.timezone()), limits,
                        e.serviceLabels() == null ? ConnectionDefinition.DEFAULT_SERVICE_LABELS : e.serviceLabels(),
                        e.applicationPackages() == null ? List.of() : e.applicationPackages(),
                        catalogue.rules(), e.scope(), e.levels() == null ? Map.of() : e.levels(), catalogue.formats(),
                        catalogue.layouts(), ignoredFrames(e.ignoredFrames()),
                        e.versionFields() == null ? ConnectionDefinition.DEFAULT_VERSION_FIELDS : e.versionFields(),
                        catalogue.systems()));
            }
            return new Config(List.copyOf(definitions), exportRoots(path, config.exportRoots(), environment));
        } catch (Exception ignored) {
            // Jackson, URI and filesystem errors can include source values. Do not retain their cause.
            throw Errors.configuration();
        }
    }

    /**
     * The rules and formats of every file of {@code rulesFile} (one path or a list), in order: the stand's own file
     * first, then generic sets. A file shared by connections is loaded once.
     */
    private static Catalogue catalogue(Path connections, JsonNode rulesFile, UnaryOperator<String> environment,
                                       Map<Path, Catalogue> loaded) {
        if (rulesFile == null || rulesFile.isNull()) return Catalogue.EMPTY;
        var files = new ArrayList<String>();
        if (rulesFile.isString()) files.add(rulesFile.asString());
        else if (rulesFile.isArray() && !rulesFile.isEmpty()) for (var file : rulesFile) {
            if (!file.isString()) throw Errors.configuration();
            files.add(file.asString());
        }
        else throw Errors.configuration();
        var rules = new ArrayList<LogRule>();
        var formats = new ArrayList<LineFormat>();
        var layouts = new ArrayList<LineLayout>();
        var systems = new ArrayList<ServiceSystem>();
        for (String file : files) {
            if (file.isBlank()) throw Errors.configuration();
            var catalogue = loaded.computeIfAbsent(rulesPath(connections, resolve(file, environment)), ConnectionsLoader::loadCatalogue);
            rules.addAll(catalogue.rules());
            formats.addAll(catalogue.formats());
            // The first file that names a layout wins: the stand's own file can redefine a generic one.
            for (var layout : catalogue.layouts())
                if (layouts.stream().noneMatch(l -> l.id().equals(layout.id()))) layouts.add(layout);
            systems.addAll(catalogue.systems());
        }
        // One file: its shared copy, so that connections naming it hold the same lists.
        return files.size() == 1 ? loaded.get(rulesPath(connections, resolve(files.getFirst(), environment))) : new Catalogue(rules, formats, layouts, systems);
    }

    /**
     * Absolute export directories; a relative one is resolved against the directory of the connections file.
     */
    private static List<Path> exportRoots(Path connections, List<String> roots, UnaryOperator<String> environment) {
        if (roots == null) return List.of();
        if (roots.isEmpty() || roots.size() > ExportRoots.MAX_ROOTS) throw Errors.configuration();
        var paths = new ArrayList<Path>();
        for (String root : roots) {
            if (root == null || root.isBlank()) throw Errors.configuration();
            paths.add(rulesPath(connections, resolve(root, environment)));
        }
        return List.copyOf(paths);
    }

    /**
     * A relative rulesFile is resolved against the directory of the connections file.
     */
    private static Path rulesPath(Path connections, String rulesFile) {
        Path rules = Path.of(rulesFile);
        if (rules.isAbsolute()) return rules.normalize();
        Path directory = connections.toAbsolutePath().getParent();
        return (directory == null ? rules : directory.resolve(rules)).normalize();
    }

    public static List<LogRule> loadRules(Path path) {
        return loadCatalogue(path).rules();
    }

    public static Catalogue loadCatalogue(Path path) {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw Errors.configuration();
            RulesFile file = MAPPER.readValue(bytes, RulesFile.class);
            if (file == null || file.rules() == null) throw Errors.configuration();
            var formats = new ArrayList<LineFormat>();
            if (file.formats() != null)
                for (Format f : file.formats()) {
                    if (f == null) throw Errors.configuration();
                    formats.add(new LineFormat(f.id(), LogRule.compile(f.pattern())));
                }
            var layouts = new ArrayList<LineLayout>();
            if (file.layouts() != null)
                for (Layout l : file.layouts()) {
                    if (l == null) throw Errors.configuration();
                    layouts.add(new LineLayout(l.id(), l.template()));
                }
            var systems = new ArrayList<ServiceSystem>();
            if (file.systems() != null)
                for (SystemEntry entry : file.systems()) {
                    if (entry == null) throw Errors.configuration();
                    systems.add(new ServiceSystem(entry.names(), entry.about(), entry.services()));
                }
            var rules = new ArrayList<LogRule>();
            for (Rule r : file.rules()) {
                if (r == null || r.category() == null) throw Errors.configuration();
                Match m = r.match() == null ? new Match(null, null, null) : r.match();
                rules.add(new LogRule(r.id(), LogRule.Category.valueOf(r.category().toUpperCase(java.util.Locale.ROOT)),
                        LogRule.compile(m.exception()), LogRule.compile(m.message()), LogRule.compile(m.logger()),
                        r.subject(), r.advice(), r.filter()));
            }
            return new Catalogue(List.copyOf(rules), List.copyOf(formats), List.copyOf(layouts), List.copyOf(systems));
        } catch (Exception ignored) {
            // Same policy as the connections file: never retain parser messages that quote the source.
            throw Errors.configuration();
        }
    }

    private static List<Pattern> ignoredFrames(List<String> patterns) {
        if (patterns == null) return List.of();
        var compiled = new ArrayList<Pattern>();
        for (String pattern : patterns) {
            if (pattern == null) throw Errors.configuration();
            compiled.add(LogRule.compile(pattern));
        }
        return compiled;
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

    /**
     * What a rules catalogue holds: the rules, tried in order, the plain-text line formats, tried in order, the line
     * templates of exportLogs and the names of the parts of the project.
     */
    public record Catalogue(List<LogRule> rules, List<LineFormat> formats, List<LineLayout> layouts, List<ServiceSystem> systems) {
        static final Catalogue EMPTY = new Catalogue(List.of(), List.of(), List.of(), List.of());
    }

    /**
     * The whole connections file: the connections and the export directories it names (empty: none).
     */
    public record Config(List<ConnectionDefinition> connections, List<Path> exportRoots) {
    }

    private record FileConfig(Map<String, Entry> connections, List<String> exportRoots) {
    }

    private record Entry(String description, String hint, String url, Auth auth, String tenant, String timezone,
                         Limits limits, List<String> serviceLabels, List<String> applicationPackages,
                         JsonNode rulesFile, String scope, Map<String, String> levels, List<String> ignoredFrames,
                         LinkedHashMap<String, String> versionFields) {
    }

    private record SystemEntry(List<String> names, String about, List<String> services) {
    }

    private record RulesFile(List<Rule> rules, List<Format> formats, List<Layout> layouts, List<SystemEntry> systems) {
    }

    private record Layout(String id, String template) {
    }

    private record Format(String id, String pattern) {
    }

    private record Rule(String id, String category, Match match, String subject, String advice, String filter) {
    }

    private record Match(String exception, String message, String logger) {
    }

    private record Auth(ConnectionAuth.Type type, String username, String password, String token) {
    }

    private record Limits(Integer connectTimeoutMs, Integer requestTimeoutMs, Integer maxHttpResponseBytes,
                          Integer maxResponseBytes, Integer maxEntries, Long maxIntervalSeconds,
                          Integer maxExportLines, Long maxExportBytes) {
    }
}
