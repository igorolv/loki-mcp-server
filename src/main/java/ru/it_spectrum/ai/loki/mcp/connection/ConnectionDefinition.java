package ru.it_spectrum.ai.loki.mcp.connection;

import java.net.URI;
import java.time.ZoneId;
import java.util.List;
import ru.it_spectrum.ai.loki.mcp.service.Errors;

/** hint is shown to the model by listConnections; serviceLabels are tried in order to name the service of a line. */
public record ConnectionDefinition(String name, String description, String hint, URI url, ConnectionAuth auth,
                                   String tenant, ZoneId timezone, ConnectionLimits limits, List<String> serviceLabels) {
    public static final List<String> DEFAULT_SERVICE_LABELS = List.of(
            "applicationName", "service_name", "service", "app", "container", "job");

    public ConnectionDefinition(String name, String description, URI url, ConnectionAuth auth,
                                String tenant, ZoneId timezone, ConnectionLimits limits) {
        this(name, description, null, url, auth, tenant, timezone, limits, DEFAULT_SERVICE_LABELS);
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
                || serviceLabels.stream().anyMatch(l -> l == null || !l.matches("[a-zA-Z_][a-zA-Z0-9_]*"))) {
            throw Errors.configuration();
        }
        serviceLabels = List.copyOf(serviceLabels);
    }

    @Override public String toString() { return "ConnectionDefinition[redacted]"; }
}
