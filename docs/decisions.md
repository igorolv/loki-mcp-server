# Design decisions

This page records the decisions that shape the current server. Earlier iterations, measurements and work packages remain in Git history.

## Five-tool interface (2026-09-27)

The user chose a small, explicit mechanism over Java-side incident interpretation. The public tools are listConnections, discoverLogs, queryLogs, countLogs and exportLogs. A connection is always explicit. queryLogs, countLogs and exportLogs take one required LogQL **log query**; the server does not guess the selector, service, level or text filter. The stand's hint explains useful selectors and fields. countLogs builds count_over_time mechanically because sending full lines merely to count them wastes model tokens. exportLogs is separate because it writes a local file and can page forward across a large window. On 2026-09-27, splitByService was removed: each export writes one file, regardless of service labels. Named export layouts were removed too; exportLogs accepts raw or an inline template.

Removed from the public interface and implementation: summarizeLogs, getLogContext, service/level/text query building, system-name expansion, rule-based causes and noise, restart/deploy inference, and automatic explanation of empty results. The target model owns interpretation and chooses what to query next. This reverses the previous decision to make summary a usual investigation step; it is a new explicit user decision.

The older summary compressed 171 large Java error lines into 35 groups and 7.8 KB on one stand. That measurement established potential token savings, but it did not establish better answers from the target model. Keeping its stack parser, rules, restart inference and several profile fields made the server hard to maintain. The five-tool design keeps compact line rendering and disk export as the bounded ways to avoid sending huge JSON lines to the model.

## Standard Spring AI mechanisms (2026-09-27)

The user chose to follow Spring AI instead of replacing its behaviour: when a standard mechanism contradicts the current approach, the approach changes. With Spring AI 2.1.0-M1 (on Spring Boot 4.2.0-M2) tools are `@Component` beans with `@McpTool` methods found by the annotation scanner; the former wrapper that built tool specifications by hand is removed. The MCP SDK validates arguments against the input schema and answers `Tool (<name>) input validation failed: …`; the JVM locale is fixed to English so that such library messages stay English. Spring AI turns an exception from a tool into an error result with its message, followed on a second line by the message of its root cause; the server's exceptions carry `Error <CODE>: <text>`, so the model sees the code and, for now, the text twice. The user accepted this standard text, including the messages of unexpected exceptions, which the wrapper used to replace with INTERNAL_ERROR. The services enforce the response budget; there is no second size check after them. A Spring aspect writes the per-call diagnostic line and sets the connection in the MDC.

`immediateExecution(true)` stays although stdio defaults to offloading tool calls: MCP SDK 2.0.0 and 2.0.1 drop responses of concurrent tool calls on stdio (java-sdk #686, fixed upstream in 2bb1481). Calls therefore run one at a time on the stdio reader thread, and a long call delays every later message, including pings and cancellations.

## Text contract

Every tool response is readable text; no output schema, structuredContent, stream dictionaries, cache, entry IDs, field projections or cursors. queryLogs shows a bounded page from either end of the window in chronological order, defaulting to newest. Its default view shortens messages and stack traces. raw=true is a preview of the line returned by Loki and result stream labels, capped at 4000 code points. Complete returned lines are available through exportLogs. When limit + 1 fits maxEntries, queryLogs asks Loki for one extra line and displays at most limit: the extra line confirms that more exist, while exactly limit without an extra line is complete. At limit = maxEntries, exactly limit still says more **may** exist. Its end or start continuation rereads the boundary millisecond; duplicate lines are acceptable. Lookahead does not resolve a timestamp that fills the page; the model must narrow the query.

These earlier cancelled designs do not return without a new user decision: JSON responses, output schemas, cursors, event cache, stream IDs and projections.

## Connection profile

connections.json is external, strict and loaded once without probes. The model sees name, description and hint, never the URL or credentials. serviceLabels supply display names, not query builders. One optional formatFile contains plain-line parsing formats and an optional standalone frame pattern. It replaces the old rules catalogue, which also held system aliases, causes and restart-specific knowledge. A format file contains rendering knowledge only. Code defaults for JSON fields are generic ECS, logstash and Serilog.

Every data operation names its connection. A failure of one connection does not mark the others unavailable. Loki 2.6.1 and 3.x use the shared read endpoints; a 404 is an endpoint failure, not a verdict on the stand.

## Count, discovery and export

Initially, discoverLogs called labels or label/<name>/values only. It did not sample lines or try to supply a ready selector. It had no selector argument because [Loki 2.6.1's metadata handler](https://github.com/grafana/loki/blob/v2.6.1/pkg/loghttp/labels.go) ignores the `query` parameter. Claiming a scoped result from that endpoint would be misleading. The later `/series` decision below adds scoped discovery through a different endpoint. countLogs wraps a log query in count_over_time and can group by label or clock aligned time bucket. It reports counts without spike or cause markers. exportLogs reads forward, writes the Loki query result line or an inline template in full and stays inside configured export roots. A LogQL line_format stage rewrites the result line before export; raw means the returned line, not necessarily the ingested line. It never overwrites an existing file. A nanosecond holding more lines than one Loki page may leave lines missing; the report says so. Export limits and errors leave a partial file with a continuation when possible.

## Boundaries and safety

The runtime only reads Loki; test ingestion is confined to integration containers. stdout is JSON-RPC only, own diagnostics go to stderr and a rolling file. Log lines are data, including text resembling instructions, and never become a query or a command. URLs, credentials, tenant, upstream bodies other than bounded LogQL query errors, and full events are excluded from diagnostics. The MCP SDK validates input; the services return safe text errors and enforce maxResponseBytes.

## Verification

Unit and loopback tests cover transport, parsing, budgets, file safety and the five-tool contract. StdioSmokeTest launches the jar over JSON-RPC. Container compatibility tests use pinned Loki 2.6.1 and 3.6.0 images and write only to those containers. Live smoke is read only and needs an explicitly configured stand.

## Investigation flow refinements (2026-09-27)

After a DeepSeek 4.1 Flash investigation of a three-day dev window, the user chose four additions. countLogs and discoverLogs get separate seven-day default window limits (`maxCountIntervalSeconds` and `maxDiscoveryIntervalSeconds`); queryLogs and exportLogs retain `maxIntervalSeconds` at one day. The per-request timeout stays at 30 seconds by default. A large count can still time out, and serialized stdio calls can delay other requests; the model should narrow the window after a timeout.

countLogs accepts an optional `step` from 1 second through 1 day for time grouping; without it, the existing automatic clock-aligned step remains. A 1d step is a fixed UTC-aligned 24-hour bucket, not a local calendar day. `groupBy="<label>,time"` counts each label value in time buckets, with explicit truncation when the result is too large. queryLogs accepts `order="newest"` (default) or `order="oldest"`; the latter uses forward Loki reads and a ready-made `start` to continue. Both directions may repeat boundary lines, and a crowded timestamp can stall pagination.

A connection format file may declare a `framePattern` for standalone stack-frame lines. Compact queryLogs output folds adjacent matching lines with the same query-result labels into a counted line; raw previews and exports retain the returned lines. Query-result labels do not prove an original stream identity, so the folding is a display aid only. No project-specific frame syntax is embedded in generic code.

## Time limits after the OpenCode investigation (2026-09-28)

The user chose to retain the 30-second timeout for one Loki request and the one-day maximum window for queryLogs and exportLogs. A single window size does not predict query cost: one-day DEV label counts took about 8–9 seconds, while two five-day counts by instance reached 30 seconds; five-day time-only buckets finished in about 13 seconds. The default maxCountIntervalSeconds is now one day for totals, label groups and combined label/time buckets. A separate maxTimeCountIntervalSeconds keeps seven days for time-only buckets. A timed-out request advises a one-day subwindow or narrower LogQL query, then a still shorter window if needed. The server does not automatically split and retry counts.

exportLogs has a 25-second default total duration, maxExportDurationMs. Each page request uses the smaller of requestTimeoutMs and the remaining export duration. When time runs out after writing lines, the existing partial-file report gives a boundary for the next export; before any line, OPERATION_TIMEOUT reports the failed attempt without creating a file. This bounds a multi-page call while stdio execution is serialized. Connection limits can override these defaults.

## Visible connection limits (2026-09-28)

The user chose to show the effective planning limits in listConnections, before the model calls a data tool. Each connection keeps its name, description and hint, followed by one compact text line with the discoverLogs, queryLogs, countLogs and exportLogs maximum windows, the separate countLogs time-only window, the queryLogs line cap and the per-request timeout. Values come from the loaded ConnectionLimits, including overrides. The response remains text and excludes the URL, credentials, tenant and full configuration.

## Stream label combinations in discovery (2026-09-28)

The user chose to keep the five-tool catalogue and extend discoverLogs with an optional single `match` stream selector. Without match it still calls labels or label/<name>/values. With match and without label it calls `/loki/api/v1/series` and shows complete stream label sets; with both match and label it derives distinct values of that label from the returned sets. The earlier decision to avoid scoped `query` on Loki 2.6.1 label endpoints remains valid: that handler ignores query, whereas `/series` applies its match selector. Multiple `match[]` selectors are not needed in the first version; their union semantics add cost and complexity to the small-model interface.

The series path has a one-day default maximum window, separate from the seven-day unscoped discovery window, and uses the same request timeout, HTTP body limit and text budget as other data calls. It displays at most 50 complete label sets or 200 scoped values, with an exact total and a visible cut only after receiving the complete Loki response. An oversized HTTP response yields no partial result or count and advises narrowing match or the window. Stream label sets do not count log lines and do not prove that a line exists exactly in the requested window. The server does not infer a selector or interpret incident causes from the sets.

## Open items

- Measure the five-tool flow on representative investigations with the intended small model. Compare answer quality, calls, latency and text volume to the older summary flow before adding any interpretation again.
- A CLI for human exports is deferred.
- Grafana proxy transport and Explore links are deferred until a real stand requires them.
- Docker packaging is deferred; the stdio jar is launched by a local MCP client.
- A crowded single timestamp cannot be paged completely through Loki's simple time boundary; the tools report this limit.
- Remove `immediateExecution(true)` once the MCP SDK brought by Spring AI contains the stdio fix for java-sdk #686 (the disabled concurrency test in jdbc-mcp-server is the reference check).
- Spring AI repeats the error text on a second line when an exception has no cause; take the fix when Spring AI changes it.
- A single queryLogs or countLogs request can still occupy serialized stdio execution for up to requestTimeoutMs. Revisit that default with more representative latency measurements, not by increasing it to make broad counts succeed.
- OpenCode 2.0.18 stalled on `loki.listConnections` in two new sessions on 2026-09-28; the calls were interrupted after about 80 and 26 seconds. The MCP process had initialized and registered five tools, but its diagnostics recorded neither invocation. A separate stdio call to the same jar on Java 25 returned `listConnections` in 26 ms. Closing and reopening the desktop window did not restart the OpenCode backend or its MCP child process; both kept their earlier PIDs. Investigate the OpenCode-to-MCP call path after a full backend restart if this recurs; these observations do not identify its exact failure point.
- In the subsequent successful OpenCode five-day investigation on 2026-09-28, the model attempted 1 listConnections, 6 discoverLogs, 22 countLogs and 18 queryLogs calls. Server diagnostics show two five-day DEV label-grouped counts timing out upstream at 30 seconds. A paired TST count completed on the server after OpenCode's Promise.all wrapper had already failed, so the model did not receive that result. The model retried with one-day windows. Three queryLogs calls exceeded the connection's one-day window limit before the model used shorter windows. The visible limits address that planning gap; this run does not establish a safe automatic split strategy.
- In that investigation, a five-day `groupBy="time", step="1d"` call returned six clock-aligned buckets, including lines outside the requested window; the response named the aligned interval, but the model described its total as a five-day count. Review whether the text should show requested and counted intervals separately. The model also treated repeated failure-report lines as a restart rate and empty samples as proof a service was clean; counts and samples do not support those conclusions by themselves.
