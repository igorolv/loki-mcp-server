package ru.it_spectrum.ai.loki.mcp.connection;

import ru.it_spectrum.ai.loki.mcp.service.Errors;

import java.net.URI;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * hint is shown to the model by listConnections; serviceLabels are tried in order to name the service of a line;
 * applicationPackages tell which stack frames belong to the stand's own code (empty: none are recognised);
 * rules say what known kinds of lines mean, in the order they are tried; scope is the stream selector of the stand's
 * services that a query built from service, level and text starts from (null: none); levels map a level name to the
 * LogQL line filter that selects it, on top of {@link #DEFAULT_LEVELS}; formats split plain-text lines into fields,
 * in the order they are tried; layouts are the named line templates exportLogs can write lines in; ignoredFrames are
 * frames of the stand's own code that are never where a failure comes from (a request filter every call passes);
 * versionFields name the JSON fields that tell a build, each with the word printed before its value; systems (from the
 * rules catalogue) are the names people use for parts of the project, each standing for several services.
 */
public record ConnectionDefinition(String name, String description, String hint, URI url, ConnectionAuth auth,
                                   String tenant, ZoneId timezone, ConnectionLimits limits,
                                   List<String> serviceLabels, List<String> applicationPackages, List<LogRule> rules,
                                   String scope, Map<String, String> levels, List<LineFormat> formats,
                                   List<LineLayout> layouts, List<Pattern> ignoredFrames, Map<String, String> versionFields,
                                   List<ServiceSystem> systems) {
    public static final List<String> DEFAULT_SERVICE_LABELS = List.of(
            "service_name", "service", "app", "container", "job");
    public static final int MAX_APPLICATION_PACKAGES = 32;
    public static final int MAX_RULES = 200;
    public static final int MAX_FILTER_CHARS = 300;
    public static final int MAX_FORMATS = 32;
    public static final int MAX_LAYOUTS = 32;
    public static final int MAX_IGNORED_FRAMES = 32;
    public static final int MAX_VERSION_FIELDS = 8;
    public static final int MAX_SYSTEMS = 64;
    /**
     * The ECS field of the release; a profile names the fields of its own builds.
     */
    public static final Map<String, String> DEFAULT_VERSION_FIELDS = Map.of("service.version", "");
    /**
     * Line filters of a level when the profile names none: the level word as most loggers print it.
     */
    public static final Map<String, String> DEFAULT_LEVELS = Map.of(
            "error", "|~ \"ERROR|FATAL\"",
            "warn", "|~ \"WARN\"");

    public ConnectionDefinition(String name, String description, String hint, URI url, ConnectionAuth auth,
                                String tenant, ZoneId timezone, ConnectionLimits limits, List<String> serviceLabels,
                                List<String> applicationPackages, List<LogRule> rules, String scope, Map<String, String> levels,
                                List<LineFormat> formats, List<LineLayout> layouts) {
        this(name, description, hint, url, auth, tenant, timezone, limits, serviceLabels, applicationPackages, rules, scope, levels,
                formats, layouts, List.of(), DEFAULT_VERSION_FIELDS, List.of());
    }

    public ConnectionDefinition(String name, String description, String hint, URI url, ConnectionAuth auth,
                                String tenant, ZoneId timezone, ConnectionLimits limits, List<String> serviceLabels,
                                List<String> applicationPackages, List<LogRule> rules, String scope, Map<String, String> levels,
                                List<LineFormat> formats) {
        this(name, description, hint, url, auth, tenant, timezone, limits, serviceLabels, applicationPackages, rules, scope, levels,
                formats, List.of());
    }

    public ConnectionDefinition(String name, String description, String hint, URI url, ConnectionAuth auth,
                                String tenant, ZoneId timezone, ConnectionLimits limits, List<String> serviceLabels,
                                List<String> applicationPackages, List<LogRule> rules) {
        this(name, description, hint, url, auth, tenant, timezone, limits, serviceLabels, applicationPackages, rules, null, Map.of());
    }

    public ConnectionDefinition(String name, String description, String hint, URI url, ConnectionAuth auth,
                                String tenant, ZoneId timezone, ConnectionLimits limits, List<String> serviceLabels,
                                List<String> applicationPackages, List<LogRule> rules, String scope, Map<String, String> levels) {
        this(name, description, hint, url, auth, tenant, timezone, limits, serviceLabels, applicationPackages, rules, scope, levels, List.of());
    }

    public ConnectionDefinition(String name, String description, URI url, ConnectionAuth auth,
                                String tenant, ZoneId timezone, ConnectionLimits limits) {
        this(name, description, null, url, auth, tenant, timezone, limits, DEFAULT_SERVICE_LABELS, List.of(), List.of());
    }

    public ConnectionDefinition(String name, String description, String hint, URI url, ConnectionAuth auth,
                                String tenant, ZoneId timezone, ConnectionLimits limits, List<String> serviceLabels) {
        this(name, description, hint, url, auth, tenant, timezone, limits, serviceLabels, List.of(), List.of());
    }

    public ConnectionDefinition(String name, String description, String hint, URI url, ConnectionAuth auth,
                                String tenant, ZoneId timezone, ConnectionLimits limits, List<String> serviceLabels,
                                List<String> applicationPackages) {
        this(name, description, hint, url, auth, tenant, timezone, limits, serviceLabels, applicationPackages, List.of());
    }

    public ConnectionDefinition {
        if (!validName(name) || (description != null && description.length() > 512)
                || (hint != null && hint.length() > 1024)
                || url == null || url.getHost() == null
                || !("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme()))
                || url.getRawUserInfo() != null || url.getRawQuery() != null || url.getRawFragment() != null
                || url.getPort() > 65535 || url.getPort() == 0
                || auth == null || timezone == null || limits == null
                || (tenant != null && (tenant.isBlank() || tenant.chars().anyMatch(Character::isISOControl)))
                || serviceLabels == null || serviceLabels.isEmpty()
                || serviceLabels.stream().anyMatch(l -> l == null || !l.matches("[a-zA-Z_][a-zA-Z0-9_]*"))
                || applicationPackages == null || applicationPackages.size() > MAX_APPLICATION_PACKAGES
                || applicationPackages.stream().anyMatch(p -> p == null || !p.matches("[a-zA-Z_$][\\w$]*(?:\\.[a-zA-Z_$][\\w$]*)*"))
                || rules == null || rules.size() > MAX_RULES || rules.stream().anyMatch(java.util.Objects::isNull)
                || rules.stream().map(LogRule::id).distinct().count() != rules.size()
                || (scope != null && (scope.length() > 1000 || !ru.it_spectrum.ai.loki.mcp.service.DiscoveryService.SELECTOR.matcher(scope).matches()))
                || levels == null || levels.size() > 16
                || levels.entrySet().stream().anyMatch(l -> l.getKey() == null || !l.getKey().matches("[a-z]{1,16}") || l.getValue() == null
                || !l.getValue().strip().startsWith("|") || l.getValue().length() > MAX_FILTER_CHARS
                || l.getValue().chars().anyMatch(Character::isISOControl))
                || formats == null || formats.size() > MAX_FORMATS || formats.stream().anyMatch(java.util.Objects::isNull)
                || formats.stream().map(LineFormat::id).distinct().count() != formats.size()
                || layouts == null || layouts.size() > MAX_LAYOUTS || layouts.stream().anyMatch(java.util.Objects::isNull)
                || layouts.stream().map(LineLayout::id).distinct().count() != layouts.size()
                || ignoredFrames == null || ignoredFrames.size() > MAX_IGNORED_FRAMES || ignoredFrames.stream().anyMatch(java.util.Objects::isNull)
                || versionFields == null || versionFields.isEmpty() || versionFields.size() > MAX_VERSION_FIELDS
                || versionFields.entrySet().stream().anyMatch(f -> f.getKey() == null || !f.getKey().matches("[\\w.@-]{1,64}")
                || f.getValue() == null || !f.getValue().matches("[\\w -]{0,16}"))
                || systems == null || systems.size() > MAX_SYSTEMS || systems.stream().anyMatch(java.util.Objects::isNull)
                || systems.stream().flatMap(s -> s.names().stream()).map(n -> n.toLowerCase(java.util.Locale.ROOT)).distinct().count()
                   != systems.stream().mapToLong(s -> s.names().size()).sum()) {
            throw Errors.configuration();
        }
        serviceLabels = List.copyOf(serviceLabels);
        applicationPackages = List.copyOf(applicationPackages);
        rules = List.copyOf(rules);
        scope = scope == null ? null : scope.strip();
        levels = java.util.Collections.unmodifiableMap(new TreeMap<>(levels));
        formats = List.copyOf(formats);
        layouts = List.copyOf(layouts);
        ignoredFrames = List.copyOf(ignoredFrames);
        versionFields = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(versionFields));
        systems = List.copyOf(systems);
    }

    /**
     * The system the name stands for, or null.
     */
    public ServiceSystem system(String name) {
        for (var system : systems) if (system.named(name)) return system;
        return null;
    }

    public static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");
    }

    /**
     * The profile's levels over the defaults, by name.
     */
    public Map<String, String> allLevels() {
        var all = new TreeMap<>(DEFAULT_LEVELS);
        all.putAll(levels);
        return all;
    }

    @Override
    public String toString() {
        return "ConnectionDefinition[redacted]";
    }
}
