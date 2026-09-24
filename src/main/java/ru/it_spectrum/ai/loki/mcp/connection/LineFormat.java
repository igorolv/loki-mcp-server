package ru.it_spectrum.ai.loki.mcp.connection;

import ru.it_spectrum.ai.loki.mcp.service.Errors;

import java.util.regex.Pattern;

/**
 * A plain-text line layout of a rules catalogue: a regular expression over the first line of a line that is not JSON,
 * whose named groups become the line's fields. {@code message} is required and replaces the whole line as the message;
 * {@code level}, {@code logger} and {@code service} are read like the JSON fields of those names; any other group
 * ({@code thread}, {@code pid}, …) is a field of the line. The first format that finds a match wins.
 */
public record LineFormat(String id, Pattern pattern) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    public LineFormat {
        if (id == null || !ID.matcher(id).matches() || pattern == null || !pattern.namedGroups().containsKey("message"))
            throw Errors.configuration();
    }

    @Override
    public String toString() {
        return "LineFormat[" + id + "]";
    }
}
