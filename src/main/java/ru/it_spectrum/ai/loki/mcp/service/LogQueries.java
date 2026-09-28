package ru.it_spectrum.ai.loki.mcp.service;

import ru.it_spectrum.ai.loki.mcp.error.Errors;

final class LogQueries {
    private LogQueries() {
    }

    static void requireLogQuery(String query) {
        if (query == null || query.isBlank()) {
            throw Errors.invalid("query is required: pass a LogQL log query such as {app=\"backend\"} |= \"ERROR\".");
        }
        if (!query.strip().startsWith("{")) {
            throw Errors.invalid("A log query starts with a stream selector, e.g. {app=\"backend\"} |= \"ERROR\". "
                    + "Use countLogs to count lines.");
        }
    }
}
