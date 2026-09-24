package ru.it_spectrum.ai.loki.mcp.connection;

import ru.it_spectrum.ai.loki.mcp.service.Errors;

import java.util.List;
import java.util.Locale;

/**
 * A name people use for a part of the stand ({@code ССЖ}, {@code ssj}) and the services it stands for, as the stand's
 * service labels hold them. {@code service="ССЖ"} in a query means all of them; the first name is the one printed.
 *
 * @param names    names of the system, matched without regard to case
 * @param about    what the system is, in a few words, or null
 * @param services the service names it stands for
 */
public record ServiceSystem(List<String> names, String about, List<String> services) {
    public static final int MAX_NAMES = 8;
    public static final int MAX_SERVICES = 32;
    public static final int MAX_ABOUT_CHARS = 200;

    public ServiceSystem {
        if (names == null || names.isEmpty() || names.size() > MAX_NAMES || names.stream().anyMatch(n -> !validName(n))
                || (about != null && (about.isBlank() || about.length() > MAX_ABOUT_CHARS || about.chars().anyMatch(Character::isISOControl)))
                || services == null || services.isEmpty() || services.size() > MAX_SERVICES
                || services.stream().anyMatch(s -> !validName(s))) {
            throw Errors.configuration();
        }
        names = List.copyOf(names);
        services = List.copyOf(services);
    }

    /**
     * One name or service: up to 64 characters without commas, quotes, backslashes or control characters, as a
     * comma-separated {@code service} argument can carry it.
     */
    private static boolean validName(String name) {
        return name != null && !name.isBlank() && name.equals(name.strip()) && name.length() <= 64
               && name.chars().noneMatch(c -> c == ',' || c == '"' || c == '\\' || Character.isISOControl(c));
    }

    public boolean named(String name) {
        String wanted = name.strip().toLowerCase(Locale.ROOT);
        return names.stream().anyMatch(n -> n.toLowerCase(Locale.ROOT).equals(wanted));
    }

    /**
     * {@code ССЖ (ssj): deposit insurance}.
     */
    @Override
    public String toString() {
        String text = names.getFirst() + (names.size() > 1 ? " (" + String.join(", ", names.subList(1, names.size())) + ")" : "");
        return about == null ? text : text + ": " + about;
    }
}
