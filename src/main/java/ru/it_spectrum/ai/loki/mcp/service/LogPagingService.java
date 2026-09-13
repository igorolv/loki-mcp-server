package ru.it_spectrum.ai.loki.mcp.service;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.loki.mcp.model.*;
import ru.it_spectrum.ai.loki.mcp.model.QueryResults.*;
import ru.it_spectrum.ai.loki.mcp.service.LogCursorCodec.State;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public final class LogPagingService {
    public static final String DRAFT_META = "loki.internal.pageDraft";
    private static final JsonMapper MAPPER = new JsonMapper();
    private final QueryService queries;
    private final ConnectionRegistry registry;
    private final LogCursorCodec codec;
    public LogPagingService(QueryService queries, ConnectionRegistry registry, LogCursorCodec codec) {
        this.queries = queries; this.registry = registry; this.codec = codec;
    }
    public Draft first(String connection, String query, String start, String end, String direction, Integer limit, List<String> fields) {
        var selected = ResponseProjection.fields(fields);
        int maximum = registry.require(connection).limits().maxEntries();
        int pageSize = limit == null ? maximum : limit;
        QueryTime.require(pageSize > 0 && pageSize <= maximum);
        var logs = queries.logs(connection, query, start, end, direction, maximum);
        var state = new State(connection, query, logs.window().startNanos(), logs.window().endNanos(), logs.direction(),
                pageSize, selected, codec.expiry(), null, 0, "");
        return prepare(logs, state, maximum);
    }
    public Draft next(String connection, String cursor) {
        int maximum = registry.require(connection).limits().maxEntries();
        var state = codec.decode(connection, cursor);
        String start = state.direction().equals("forward") ? state.boundary() : state.start();
        // Loki end is exclusive. Widen by 1 ns to INCLUDE the boundary, never to skip it.
        String end = state.direction().equals("backward")
                ? Long.toString(Math.min(Long.parseLong(state.end()), Math.addExact(Long.parseLong(state.boundary()), 1))) : state.end();
        var logs = queries.logs(connection, state.query(), start, end, state.direction(), maximum);
        return prepare(logs, state, maximum);
    }
    private Draft prepare(Logs logs, State state, int maximum) {
        record Identified(Event event, String hash) {}
        var ordered = new ArrayList<Identified>();
        long start = Long.parseLong(logs.window().startNanos()), end = Long.parseLong(logs.window().endNanos());
        for (var event : logs.events()) {
            long timestamp = Long.parseLong(event.timestampNanos());
            if (timestamp < start || timestamp >= end) throw Errors.failure(ErrorCode.UPSTREAM_INVALID_RESPONSE, "Upstream returned an event outside the requested window.");
            ordered.add(new Identified(event, fingerprint(event)));
        }
        Comparator<Identified> time = Comparator.comparingLong(e -> Long.parseLong(e.event().timestampNanos()));
        if (state.direction().equals("backward")) time = time.reversed();
        ordered.sort(time.thenComparing(Identified::hash));
        int skip = state.consumed();
        if (skip > ordered.size()) throw stale();
        String digest = "";
        for (int i = 0; i < skip; i++) {
            var entry = ordered.get(i);
            if (!entry.event().timestampNanos().equals(state.boundary())) throw stale();
            digest = chain(digest, entry.hash());
        }
        if (skip > 0 && !digest.equals(state.digest())) throw stale();
        var events = new ArrayList<Event>();
        var checkpoints = new ArrayList<State>();
        String boundary = state.boundary();
        int consumed = skip;
        for (int i = skip; i < ordered.size() && events.size() < state.pageSize(); i++) {
            var entry = ordered.get(i);
            if (!entry.event().timestampNanos().equals(boundary)) {
                boundary = entry.event().timestampNanos(); consumed = 0; digest = "";
            }
            consumed++; digest = chain(digest, entry.hash());
            events.add(entry.event());
            checkpoints.add(new State(state.connection(), state.query(), state.start(), state.end(), state.direction(),
                    state.pageSize(), state.fields(), state.expiresAt(), boundary, consumed, digest));
        }
        var limitations = new ArrayList<>(logs.limitations());
        limitations.add("REQUERY_NO_SNAPSHOT_LATE_ARRIVALS_MAY_BE_MISSED");
        if (ordered.size() - skip > events.size()) limitations.add("PAGE_ENTRY_LIMIT");
        var page = new Logs(logs.connection(), logs.window(), logs.direction(), state.pageSize(), logs.readEntries(),
                events.size(), logs.resultStreams(), logs.upstreamLimitReached(),
                ordered.size() - skip > events.size() ? Completeness.PARTIAL : logs.completeness(),
                "END_OF_RESULTS", logs.totalLinesProcessed(), limitations, events);
        var compact = ResponseProjection.project(page, state.fields());
        var result = new CompactLogs(compact.connection(), compact.window(), compact.direction(), compact.limit(), compact.readEntries(),
                compact.returnedEntries(), compact.resultStreams(), compact.upstreamLimitReached(), compact.completeness(),
                compact.continuationUnavailableReason(), compact.totalLinesProcessed(), compact.fields(), compact.limitations(),
                compact.streams(), compact.events(), null, maximum, new Window(state.start(), state.end()));
        return new Draft(result, checkpoints, ordered.size() - skip, maximum, codec);
    }

    /** Exists only until this response is written. Contains hashes/parameters, never full source events. */
    public record Draft(CompactLogs result, List<State> checkpoints, int available, int fetchLimit, LogCursorCodec codec) {
        public Draft { checkpoints = List.copyOf(checkpoints); }
        public void finish(ObjectNode payload) {
            int count = payload.path("events").size();
            payload.remove("nextCursor");
            String reason = "END_OF_RESULTS";
            if (count == 0 && available > 0) reason = "RESPONSE_BUDGET_NO_PROGRESS";
            else if (count > 0 && (count < available || result.upstreamLimitReached())) {
                var checkpoint = checkpoints.get(count - 1);
                if (count == available && checkpoint.consumed() >= fetchLimit) reason = "BOUNDARY_SATURATED";
                else {
                    String cursor = codec.encode(checkpoint);
                    if (cursor == null) reason = "CURSOR_STATE_TOO_LARGE";
                    else { payload.put("nextCursor", cursor); reason = "NONE"; }
                }
            } else if (result.upstreamLimitReached()) reason = "BOUNDARY_SATURATED";
            else if (result.completeness() == Completeness.UNKNOWN) reason = "UPSTREAM_WARNINGS_NO_SAFE_CONTINUATION";
            payload.put("continuationUnavailableReason", reason);
        }
        @Override public String toString() { return "LogPageDraft[redacted]"; }
    }
    private static String fingerprint(Event event) {
        return hash(MAPPER.writeValueAsString(List.of(event.timestampNanos(),
                new TreeMap<>(event.resultLabels()), event.line(), new TreeMap<>(event.structuredMetadata()))));
    }
    private static String chain(String prefix, String next) { return hash(prefix + next); }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ignored) { throw new IllegalStateException("Cannot fingerprint page."); }
    }
    private static LokiOperationException stale() {
        return Errors.failure(ErrorCode.CURSOR_STALE, "The overlap changed or cannot be verified. Start a new query; no boundary was skipped.");
    }
}
