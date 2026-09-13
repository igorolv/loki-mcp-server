package ru.it_spectrum.ai.loki.mcp.connection;

import ru.it_spectrum.ai.loki.mcp.service.Errors;

public record ConnectionAuth(Type type, String username, String password, String token) {
    public enum Type { NONE, BASIC, BEARER }
    public static final ConnectionAuth NONE = new ConnectionAuth(Type.NONE, null, null, null);

    public ConnectionAuth {
        if (type == null || switch (type) {
            case NONE -> username != null || password != null || token != null;
            case BASIC -> username == null || username.isBlank() || username.contains(":")
                    || password == null || token != null;
            case BEARER -> token == null || token.isBlank() || username != null || password != null;
        }) {
            throw Errors.configuration();
        }
        for (String value : new String[]{username, password, token}) {
            if (value != null && value.chars().anyMatch(Character::isISOControl)) {
                throw Errors.configuration();
            }
        }
    }

    @Override public String toString() { return "ConnectionAuth[redacted]"; }
}
