package ru.it_spectrum.ai.loki.mcp.connection;

import ru.it_spectrum.ai.loki.mcp.service.Errors;

import java.net.URI;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * hint is shown to the model by listConnections; serviceLabels are tried in order to name the service of a line;
 * applicationPackages tell which stack frames belong to the stand's own code (empty: none are recognised);
 * rules say what known kinds of lines mean, in the order they are tried; scope is the stream selector of the stand's
 * services that a query built from service, level and text starts from (null: none); levels map a level name to the
 * LogQL line filter that selects it, on top of {@link #DEFAULT_LEVELS}; formats split plain-text lines into fields,
 * in the order they are tried.
 */
public record ConnectionDefinition(String name, String description, String hint, URI url, ConnectionAuth auth,
                                   String tenant, ZoneId timezone, ConnectionLimits limits,
                                   List<String> serviceLabels, List<String> applicationPackages, List<LogRule> rules,
                                   String scope, Map<String, String> levels, List<LineFormat> formats) {
    public static final List<String> DEFAULT_SERVICE_LABELS = List.of(
            "applicationName", "service_name", "service", "app", "container", "job");
    public static final int MAX_APPLICATION_PACKAGES = 32;
    public static final int MAX_RULES = 200;
    public static final int MAX_FILTER_CHARS = 300;
    public static final int MAX_FORMATS = 32;
    /**
     * Line filters of a level when the profile names none: the level word as Java and most loggers print it, and for
     * errors the exception lines of a stack trace.
     */
    public static final Map<String, String> DEFAULT_LEVELS = Map.of(
            "error", "|~ \"ERROR|FATAL|Exception|Caused by\"",
            "warn", "|~ \"WARN\"");

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

    public static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");
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
                || formats.stream().map(LineFormat::id).distinct().count() != formats.size()) {
            throw Errors.configuration();
        }
        serviceLabels = List.copyOf(serviceLabels);
        applicationPackages = List.copyOf(applicationPackages);
        rules = List.copyOf(rules);
        scope = scope == null ? null : scope.strip();
        levels = java.util.Collections.unmodifiableMap(new TreeMap<>(levels));
        formats = List.copyOf(formats);
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
