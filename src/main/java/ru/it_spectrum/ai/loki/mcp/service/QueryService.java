package ru.it_spectrum.ai.loki.mcp.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.loki.mcp.client.LokiHttpClient;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionDefinition;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.error.ErrorCode;
import ru.it_spectrum.ai.loki.mcp.error.Errors;
import ru.it_spectrum.ai.loki.mcp.model.LogEvent;
import ru.it_spectrum.ai.loki.mcp.parser.EventNormalizer;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static ru.it_spectrum.ai.loki.mcp.service.LogText.frameSummary;
import static ru.it_spectrum.ai.loki.mcp.service.LogText.line;
import static ru.it_spectrum.ai.loki.mcp.service.ResponseText.ENVELOPE_BYTES;
import static ru.it_spectrum.ai.loki.mcp.service.ResponseText.assemble;
import static ru.it_spectrum.ai.loki.mcp.service.ResponseText.bytes;
import static ru.it_spectrum.ai.loki.mcp.service.ResponseText.iso;
import static ru.it_spectrum.ai.loki.mcp.service.ResponseText.window;

/**
 * Bounded log pages rendered as text.
 */
@Service
public class QueryService {
    public static final int DEFAULT_LIMIT = 50;
    private final ConnectionRegistry registry;
    private final LogEventReader reader;
    private final Clock clock;

    @Autowired
    public QueryService(ConnectionRegistry registry, LokiHttpClient client) {
        this(registry, client, Clock.systemUTC());
    }

    public QueryService(ConnectionRegistry registry, LokiHttpClient client, Clock clock) {
        this.registry = registry;
        this.reader = new LogEventReader(client);
        this.clock = clock;
    }

    public String logs(String connection, String query, String start, String end, Integer limit, Boolean raw) {
        return logs(connection, query, start, end, limit, raw, null);
    }

    public String logs(String connection, String query, String start, String end, Integer limit, Boolean raw,
                       String order) {
        var definition = registry.require(connection);
        LogQueries.requireLogQuery(query);
        String requestedOrder = order == null || order.isBlank() ? "newest" : order.strip();
        if (!requestedOrder.equals("newest") && !requestedOrder.equals("oldest")) {
            throw Errors.invalid("order must be \"newest\" or \"oldest\".");
        }
        boolean oldestFirst = requestedOrder.equals("oldest");
        var range = QueryTime.range(start, end, clock.instant(), definition.timezone(),
                definition.limits().maxIntervalSeconds());
        int usedLimit = limit == null ? Math.min(DEFAULT_LIMIT, definition.limits().maxEntries()) : limit;
        if (usedLimit <= 0 || usedLimit > definition.limits().maxEntries()) {
            throw Errors.invalid("limit must be between 1 and " + definition.limits().maxEntries() + " for this connection.");
        }
        boolean canLookAhead = usedLimit < definition.limits().maxEntries();
        var fetched = reader.readPage(connection, query, range.start(), range.end(),
                usedLimit + (canLookAhead ? 1 : 0),
                oldestFirst ? LokiHttpClient.Direction.FORWARD : LokiHttpClient.Direction.BACKWARD, null,
                "This is a metric expression; queryLogs reads log lines. Use countLogs to count them.");
        boolean hasMore = fetched.size() > usedLimit;
        var events = hasMore ? new ArrayList<>(oldestFirst
                ? fetched.subList(0, usedLimit) : fetched.subList(1, fetched.size())) : fetched;
        boolean atLimit = events.size() == usedLimit && (hasMore || !canLookAhead);
        ZoneId zone = definition.timezone();
        var rows = pageRows(events, definition, Boolean.TRUE.equals(raw));
        String header = query.strip() + " — " + connection + ", " + window(range, zone) + ", "
                + (events.isEmpty() ? "no matching lines." : atLimit
                ? requestedOrder + " " + events.size() + " lines ("
                + (hasMore ? "more exist" : "more may exist") + "):"
                : "all " + events.size() + " lines:");
        return renderPage(header, rows, events.size(), atLimit, oldestFirst, zone,
                definition.limits().maxResponseBytes() - ENVELOPE_BYTES);
    }

    private static List<PageRow> pageRows(List<LogEvent> events, ConnectionDefinition definition, boolean raw) {
        var rows = new ArrayList<PageRow>();
        var normalizer = new EventNormalizer(definition.formats(), definition.jsonFormats());
        LocalDate previousDay = null;
        for (int i = 0; i < events.size();) {
            LogEvent event = events.get(i);
            Instant first = QueryTime.fromNanos(event.timestampNanos());
            LocalDate day = first.atZone(definition.timezone()).toLocalDate();
            if (previousDay != null && !day.equals(previousDay)) {
                rows.add(new PageRow("--- " + day + " ---", null, null, 0));
            }
            previousDay = day;
            int end = i + 1;
            Pattern frame = definition.framePattern();
            if (!raw && frame != null && standaloneFrame(event.line(), frame)) {
                while (end < events.size()) {
                    LogEvent next = events.get(end);
                    Instant nextTime = QueryTime.fromNanos(next.timestampNanos());
                    if (!next.labels().equals(event.labels())
                            || !nextTime.atZone(definition.timezone()).toLocalDate().equals(day)
                            || !standaloneFrame(next.line(), frame)) break;
                    end++;
                }
            }
            var normalized = normalizer.normalize(event, definition.serviceLabels());
            String rendered = end - i > 1
                    ? frameSummary(normalized, end - i, definition.timezone())
                    : line(normalized, definition.timezone(), raw);
            Instant last = QueryTime.fromNanos(events.get(end - 1).timestampNanos());
            rows.add(new PageRow(rendered, first, last, end - i));
            i = end;
        }
        return rows;
    }

    private static boolean standaloneFrame(String line, Pattern frame) {
        return line.indexOf('\n') < 0 && line.indexOf('\r') < 0 && frame.matcher(line).matches();
    }

    private static String renderPage(String header, List<PageRow> rows, int fetched, boolean atLimit,
                                     boolean oldestFirst, ZoneId zone, int budget) {
        var shown = new ArrayList<>(rows);
        while (true) {
            int count = shown.stream().mapToInt(PageRow::lines).sum();
            String footer = pageFooter(shown, fetched, count, atLimit, oldestFirst, zone);
            String result = assemble(header, shown.stream().map(PageRow::text).toList(), footer);
            if (bytes(result) <= budget && (count > 0 || fetched == 0)) return result;
            if (shown.isEmpty()) {
                throw Errors.failure(ErrorCode.RESPONSE_BUDGET_EXCEEDED,
                        "Even a minimal response does not fit maxResponseBytes of this connection. "
                                + "Narrow the query or raise the limit.");
            }
            if (oldestFirst) shown.removeLast();
            else shown.removeFirst();
            while (!shown.isEmpty() && shown.get(oldestFirst ? shown.size() - 1 : 0).lines() == 0) {
                if (oldestFirst) shown.removeLast();
                else shown.removeFirst();
            }
        }
    }

    private static String pageFooter(List<PageRow> shownRows, int fetched, int shown, boolean atLimit,
                                     boolean oldestFirst, ZoneId zone) {
        if (fetched == 0) {
            return "Try a wider window, inspect labels with discoverLogs, or simplify the LogQL filter.";
        }
        if (shown == 0) return "";
        if (!atLimit && shown == fetched) return "Shown all " + shown + " matching lines.";
        var footer = new StringBuilder();
        if (shown < fetched) {
            footer.append("Output limit reached: showing ").append(shown)
                    .append(oldestFirst ? " oldest of " : " newest of ")
                    .append(fetched).append(" fetched lines. ");
        }
        if (oldestFirst) {
            Instant newest = shownRows.stream().filter(row -> row.lines() > 0).reduce((left, right) -> right)
                    .orElseThrow().last();
            footer.append("Newest shown ").append(iso(newest, zone)).append(". Newer: repeat with start=\"")
                    .append(iso(newest.truncatedTo(ChronoUnit.MILLIS), zone)).append("\"; boundary lines may repeat. ");
        } else {
            Instant oldest = shownRows.stream().filter(row -> row.lines() > 0).findFirst().orElseThrow().first();
            footer.append("Oldest shown ").append(iso(oldest, zone)).append(". Older: repeat with end=\"")
                    .append(iso(QueryTime.ceilMillis(oldest), zone)).append("\"; boundary lines may repeat. ");
        }
        footer.append("If the same timestamp fills every page, narrow the query. ")
                .append("For many lines, use countLogs or exportLogs.");
        return footer.toString();
    }

    private record PageRow(String text, Instant first, Instant last, int lines) {
    }
}
