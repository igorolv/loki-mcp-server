package ru.it_spectrum.ai.loki.mcp.service;

import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionDefinition;

import java.util.*;

/**
 * A LogQL log query built from what the model asks for instead of written by it: the connection's scope, the service
 * matcher on the label that holds the name (looked up among the service labels), the level's line filter and a text
 * filter. The built query is printed in the answer like a given one, so the model sees the LogQL it can refine.
 */
final class QueryIntent {
    static final int TEXT_CHARS = 500;
    static final int SERVICE_CHARS = 200;

    private QueryIntent() {
    }

    static boolean given(String service, String level, String text) {
        return present(service) || present(level) || present(text);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * {@code query} when it is given, else the query built from {@code service}, {@code level} and {@code text}; both
     * or neither is an argument error with an example.
     */
    static String resolve(ConnectionDefinition definition, LokiHttpClient client, String query, String service, String level,
                          String text, QueryTime.Range window) {
        boolean intent = given(service, level, text);
        if (present(query) && intent)
            throw Errors.invalid("Pass either query or service/level/text, not both. For example service=\"backend\", level=\"error\", "
                    + "or query={app=\"backend\"} |= \"ERROR\".");
        if (!intent) return query;
        String selector = definition.scope();
        if (present(service)) {
            String matcher = serviceMatcher(definition, client, service, window);
            selector = selector == null ? "{" + matcher + "}"
                    : selector.substring(0, selector.lastIndexOf('}')).stripTrailing() + ", " + matcher + "}";
        }
        if (selector == null)
            throw Errors.invalid("This connection has no scope for a query without a service: pass service=\"...\" (names from "
                    + "discoverLogs) or a LogQL query.");
        var built = new StringBuilder(selector);
        if (present(level)) {
            var levels = definition.allLevels();
            String filter = levels.get(level.strip().toLowerCase(Locale.ROOT));
            if (filter == null)
                throw Errors.invalid("level must be one of: " + String.join(", ", levels.keySet()) + ".");
            built.append(' ').append(filter.strip());
        }
        if (present(text)) {
            if (text.length() > TEXT_CHARS || text.chars().anyMatch(Character::isISOControl))
                throw Errors.invalid("text must be up to " + TEXT_CHARS + " characters on one line.");
            built.append(" |= \"").append(GroupHistory.logqlEscape(text)).append('"');
        }
        return built.toString();
    }

    /**
     * {@code applicationName="ssj-backend"} or {@code instance=~"ssj-main|sec-main"}: the first service label whose
     * values in the window (within the scope) hold every name.
     */
    private static String serviceMatcher(ConnectionDefinition definition, LokiHttpClient client, String service, QueryTime.Range window) {
        var names = new ArrayList<String>();
        for (String name : service.split(",")) {
            name = name.strip();
            if (name.isEmpty()) continue;
            if (name.length() > SERVICE_CHARS || name.chars().anyMatch(c -> c == '"' || c == '\\' || Character.isISOControl(c)))
                throw Errors.invalid("service must be names as discoverLogs prints them, separated by commas, without quotes.");
            names.add(name);
        }
        if (names.isEmpty()) throw Errors.invalid("service must name at least one service, e.g. service=\"backend\".");
        var found = new LinkedHashMap<String, String>();
        var all = new TreeSet<String>();
        for (String label : definition.serviceLabels()) {
            var response = client.labelValues(definition.name(), label, window.start(), window.end(), definition.scope());
            var values = response == null ? List.<String>of() : response.values();
            all.addAll(values);
            if (values.containsAll(names)) return matcher(label, names);
            for (String name : names) if (values.contains(name)) found.putIfAbsent(name, label);
        }
        var missing = names.stream().filter(n -> !found.containsKey(n)).toList();
        if (missing.isEmpty())
            throw Errors.invalid("These services are named by different labels (" + String.join(", ", new TreeSet<>(found.values()))
                    + "); ask for them one call at a time.");
        throw Errors.invalid("No service \"" + missing.getFirst() + "\" in this window (labels " + String.join(", ", definition.serviceLabels())
                + " were searched)" + (all.isEmpty() ? ". Try a wider window." : "; closest: " + String.join(", ", SelectorCheck.closest(missing.getFirst(), all)) + "."));
    }

    private static String matcher(String label, List<String> names) {
        if (names.size() == 1) return label + "=\"" + GroupHistory.logqlEscape(names.getFirst()) + "\"";
        return label + "=~\"" + GroupHistory.logqlEscape(String.join("|", names.stream().map(GroupHistory::regexEscape).toList())) + "\"";
    }
}
