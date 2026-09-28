package ru.it_spectrum.ai.loki.mcp.connection;

import ru.it_spectrum.ai.loki.mcp.parser.LineFormat;
import ru.it_spectrum.ai.loki.mcp.service.Errors;

import java.net.URI;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One isolated Loki connection. The hint explains the stand's selectors; formats only affect local rendering.
 */
public record ConnectionDefinition(String name, String description, String hint, URI url, ConnectionAuth auth,
                                   String tenant, ZoneId timezone, ConnectionLimits limits, List<String> serviceLabels,
                                   List<LineFormat> formats, Pattern framePattern) {
    public static final List<String> DEFAULT_SERVICE_LABELS = List.of("service_name", "service", "app", "container", "job");
    public static final int MAX_FORMATS = 32;

    public ConnectionDefinition(String name, String description, URI url, ConnectionAuth auth,
                                String tenant, ZoneId timezone, ConnectionLimits limits) {
        this(name, description, null, url, auth, tenant, timezone, limits, DEFAULT_SERVICE_LABELS, List.of(), null);
    }

    public ConnectionDefinition(String name, String description, String hint, URI url, ConnectionAuth auth,
                                String tenant, ZoneId timezone, ConnectionLimits limits, List<String> serviceLabels) {
        this(name, description, hint, url, auth, tenant, timezone, limits, serviceLabels, List.of(), null);
    }

    public ConnectionDefinition(String name, String description, String hint, URI url, ConnectionAuth auth,
                                String tenant, ZoneId timezone, ConnectionLimits limits, List<String> serviceLabels,
                                List<LineFormat> formats) {
        this(name, description, hint, url, auth, tenant, timezone, limits, serviceLabels, formats, null);
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
                || formats == null || formats.size() > MAX_FORMATS || formats.stream().anyMatch(java.util.Objects::isNull)
                || formats.stream().map(LineFormat::id).distinct().count() != formats.size()) {
            throw Errors.configuration();
        }
        serviceLabels = List.copyOf(serviceLabels);
        formats = List.copyOf(formats);
    }

    public static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");
    }

    @Override
    public String toString() {
        return "ConnectionDefinition[redacted]";
    }
}
