package ru.it_spectrum.ai.loki.mcp.service;

/**
 * Bounds for parsing fields from one log line.
 */
public final class EventParseLimits {
    public static final int FIELDS = 100;
    public static final int JSON_DEPTH = 20;
    public static final int PARSE_CHARACTERS = 256 * 1024;
    private EventParseLimits() {
    }
}
