package ru.it_spectrum.ai.loki.mcp.connection;

import ru.it_spectrum.ai.loki.mcp.service.Errors;
import ru.it_spectrum.ai.loki.mcp.service.LogLayout;

import java.util.regex.Pattern;

/**
 * A named line template of a rules catalogue that exportLogs writes lines in, the reverse of a {@link LineFormat}:
 * {@code format="spring"} names the layout with id {@code spring}. The template syntax is {@link LogLayout}'s.
 */
public record LineLayout(String id, String template) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    public LineLayout {
        if (id == null || !ID.matcher(id).matches() || id.equals("raw")) throw Errors.configuration();
        try {
            LogLayout.compile(template);
        } catch (RuntimeException invalid) {
            throw Errors.configuration();
        }
    }

    @Override
    public String toString() {
        return "LineLayout[" + id + "]";
    }
}
