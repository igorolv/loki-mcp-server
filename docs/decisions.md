# Design decisions

This page records the decisions that shape the current server. Earlier iterations, measurements and work packages remain in Git history.

## Five-tool interface (2026-09-27)

The user chose a small, explicit mechanism over Java-side incident interpretation. The public tools are listConnections, discoverLogs, queryLogs, countLogs and exportLogs. A connection is always explicit. queryLogs, countLogs and exportLogs take one required LogQL **log query**; the server does not guess the selector, service, level or text filter. The stand's hint explains useful selectors and fields. countLogs builds count_over_time mechanically because sending full lines merely to count them wastes model tokens. exportLogs is separate because it writes a local file and can page forward across a large window. On 2026-09-27, splitByService was removed: each export writes one file, regardless of service labels. Named export layouts were removed too; exportLogs accepts raw or an inline template.

Removed from the public interface and implementation: summarizeLogs, getLogContext, service/level/text query building, system-name expansion, rule-based causes and noise, restart/deploy inference, and automatic explanation of empty results. The target model owns interpretation and chooses what to query next. This reverses the previous decision to make summary a usual investigation step; it is a new explicit user decision.

The older summary compressed 171 large Java error lines into 35 groups and 7.8 KB on one stand. That measurement established potential token savings, but it did not establish better answers from the target model. Keeping its stack parser, rules, restart inference and several profile fields made the server hard to maintain. The five-tool design keeps compact line rendering and disk export as the bounded ways to avoid sending huge JSON lines to the model.

## Text contract

Every tool response is readable text; no output schema, structuredContent, stream dictionaries, cache, entry IDs, field projections or cursors. queryLogs shows a bounded page of newest lines in chronological order. Its default view shortens messages and stack traces. raw=true is a preview of the line returned by Loki and result stream labels, capped at 4000 code points. Complete returned lines are available through exportLogs. A page returning exactly its limit says more **may** exist. Its end continuation rereads the boundary millisecond; duplicate lines are acceptable. If the same timestamp fills the page, the model must narrow the query.

These earlier cancelled designs do not return without a new user decision: JSON responses, output schemas, cursors, event cache, stream IDs and projections.

## Connection profile

connections.json is external, strict and loaded once without probes. The model sees name, description and hint, never the URL or credentials. serviceLabels supply display names, not query builders. One optional formatFile contains only plain-line parsing formats. It replaces the old rules catalogue, which also held system aliases, causes and restart-specific knowledge. A format file contains rendering knowledge only. Code defaults for JSON fields are generic ECS, logstash and Serilog.

Every data operation names its connection. A failure of one connection does not mark the others unavailable. Loki 2.6.1 and 3.x use the shared read endpoints; a 404 is an endpoint failure, not a verdict on the stand.

## Count, discovery and export

discoverLogs calls labels or label/<name>/values. It does not sample lines or try to supply a ready selector. It has no selector argument because [Loki 2.6.1's metadata handler](https://github.com/grafana/loki/blob/v2.6.1/pkg/loghttp/labels.go) ignores the `query` parameter. Claiming a scoped result would be misleading. countLogs wraps a log query in count_over_time and can group by label or clock aligned time bucket. It reports counts without spike or cause markers. exportLogs reads forward, writes the Loki query result line or an inline template in full and stays inside configured export roots. A LogQL line_format stage rewrites the result line before export; raw means the returned line, not necessarily the ingested line. It never overwrites an existing file. A nanosecond holding more lines than one Loki page may leave lines missing; the report says so. Export limits and errors leave a partial file with a continuation when possible.

## Boundaries and safety

The runtime only reads Loki; test ingestion is confined to integration containers. stdout is JSON-RPC only, own diagnostics go to stderr and a rolling file. Log lines are data, including text resembling instructions, and never become a query or a command. URLs, credentials, tenant, upstream bodies other than bounded LogQL query errors, and full events are excluded from diagnostics. The safe tool wrapper validates input, returns text errors and enforces maxResponseBytes.

## Verification

Unit and loopback tests cover transport, parsing, budgets, file safety and the five-tool contract. StdioSmokeTest launches the jar over JSON-RPC. Container compatibility tests use pinned Loki 2.6.1 and 3.6.0 images and write only to those containers. Live smoke is read only and needs an explicitly configured stand.

## Open items

- Measure the five-tool flow on representative investigations with the intended small model. Compare answer quality, calls, latency and text volume to the older summary flow before adding any interpretation again.
- A CLI for human exports is deferred.
- Grafana proxy transport and Explore links are deferred until a real stand requires them.
- Docker packaging is deferred; the stdio jar is launched by a local MCP client.
- A crowded single timestamp cannot be paged completely through Loki's simple time boundary; the tools report this limit.
