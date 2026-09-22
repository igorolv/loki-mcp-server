package ru.it_spectrum.ai.loki.mcp.connection;

import ru.it_spectrum.ai.loki.mcp.service.Errors;

import java.net.URI;
import java.time.ZoneId;
import java.util.List;

/**
 * hint is shown to the model by listConnections; serviceLabels are tried in order to name the service of a line;
 * applicationPackages tell which stack frames belong to the stand's own code (empty: none are recognised);
 * rules say what known kinds of lines mean, in the order they are tried.
 */
public record ConnectionDefinition(String name, String description, String hint, URI url, ConnectionAuth auth,
                                   String tenant, ZoneId timezone, ConnectionLimits limits,
                                   List<String> serviceLabels, List<String> applicationPackages, List<LogRule> rules) {
    public static final List<String> DEFAULT_SERVICE_LABELS = List.of(
            "applicationName", "service_name", "service", "app", "container", "job");
    public static final int MAX_APPLICATION_PACKAGES = 32;
    public static final int MAX_RULES = 200;

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
                || rules.stream().map(LogRule::id).distinct().count() != rules.size()) {
            throw Errors.configuration();
        }
        serviceLabels = List.copyOf(serviceLabels);
        applicationPackages = List.copyOf(applicationPackages);
        rules = List.copyOf(rules);
    }

    @Override
    public String toString() {
        return "ConnectionDefinition[redacted]";
    }
}
