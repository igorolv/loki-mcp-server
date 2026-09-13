package ru.it_spectrum.ai.loki.mcp.service;

/** Central bounded discovery policy; HTTP bytes and interval remain connection-specific. */
public final class DiscoveryLimits {
    private DiscoveryLimits() {}
    public static final int SAMPLE_ENTRIES = 20;
    public static final int SERIES = 2000;
    public static final int LABELS = 30;
    /** Above this many distinct values a label is reported as high cardinality without listing. */
    public static final int VALUES_PER_LABEL = 20;
    public static final int VALUES_LISTED = 10;
    public static final int FIELDS = 100;
    public static final int FIELDS_LISTED = 30;
    public static final int EXAMPLE_CHARS = 300;
    public static final int JSON_DEPTH = 20;
    public static final int PARSE_CHARACTERS = 256 * 1024;
    public static final int SELECTOR_CHARACTERS = 8192;
}
