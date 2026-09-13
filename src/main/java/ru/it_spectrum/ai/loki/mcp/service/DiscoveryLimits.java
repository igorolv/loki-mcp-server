package ru.it_spectrum.ai.loki.mcp.service;

/** Central bounded discovery policy; HTTP bytes and interval remain connection-specific. */
public final class DiscoveryLimits {
    private DiscoveryLimits() {}
    public static final int SAMPLE_ENTRIES = 20;
    public static final int EXAMPLES = 3;
    public static final int SERIES = 100;
    public static final int LABELS = 30;
    public static final int VALUES_PER_LABEL = 20;
    public static final int FIELDS = 100;
    public static final int JSON_DEPTH = 20;
    public static final int PARSE_CHARACTERS = 256 * 1024;
    public static final int SELECTOR_CHARACTERS = 8192;
}
