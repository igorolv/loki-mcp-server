# Query, count and export contract

Every tool returns one readable text content, without an output schema or structuredContent. All data tools require an explicit connection from listConnections. queryLogs, countLogs and exportLogs require a LogQL **log query** starting with a stream selector. The same query can be passed to all three tools; metric expressions belong inside countLogs and are built by the server. The server never builds a query from log contents.

start defaults to now-1h and end to now. Accepted times: now, now-15m (units ns/ms/s/m/h/d), RFC3339 with an offset, local time in the connection timezone, or epoch nanoseconds. maxIntervalSeconds bounds queryLogs and exportLogs. maxCountIntervalSeconds bounds countLogs totals, label grouping and combined label/time grouping; maxTimeCountIntervalSeconds bounds time-only buckets. maxDiscoveryIntervalSeconds bounds discoverLogs without match; maxSeriesIntervalSeconds bounds it with match. listConnections displays these effective windows, the queryLogs maxEntries cap and requestTimeoutMs for each connection before a query. Time is kept in nanoseconds internally and printed in the connection timezone with milliseconds.

## queryLogs(connection, query, start, end, limit = 50, raw = false, order = newest)

One Loki query_range request asks for limit + 1 lines when that stays within maxEntries; otherwise it asks for limit. The extra line is used only to determine whether more lines exist beyond the page. At most limit lines are displayed. order="newest" reads backward; order="oldest" reads forward. Both pages are rendered oldest to newest. The default view displays a local time, level, service, message and compact stack trace. The date is in the header and day changes inside a page have markers. When the connection format file has a framePattern, consecutive matching standalone lines with the same query-result labels are shown as `… N stack frame lines`; those labels do not prove original stream identity.

~~~text
{app="backend"} |= "ERROR" — dev, 2026-09-13 10:00:00–11:00:00 (+03:00), all 2 lines:
10:12:03.123 ERROR backend  Connection refused
10:15:08.456 ERROR backend  Request failed
Shown all 2 matching lines.
~~~

A message is cut at 400 code points; stack traces keep a few frames and show the number skipped. raw=true displays each line returned by Loki with the query result's stream labels, cut at 4000 code points. It bypasses frame folding, is a preview and can cut JSON. exportLogs writes complete returned lines.

With lookahead, fewer than or exactly limit returned lines are "all N lines"; an extra returned line makes the header "newest N lines (more exist)" or "oldest N lines (more exist)". At limit = maxEntries, no extra line can be requested, so exactly limit returned lines say "more may exist". The extra line is not displayed, counted among fetched lines or used as a continuation boundary. The footer gives an end for older lines or a start for newer lines, rounded to a millisecond that rereads the boundary. Duplicates are possible. If more than a page of lines share one timestamp, repeating the boundary may return the same page even when lookahead confirms more lines; narrow the selector or filter. A response budget cut drops oldest displayed lines in newest order, or newest displayed lines in oldest order, and says “Output limit reached”. An empty page suggests widening the window, using discoverLogs or simplifying the filter.

## countLogs(connection, query, start, end, groupBy, step)

Without groupBy, the server sends sum(count_over_time(<query> [<window>])) as an instant query at end and returns one count. groupBy="<label>" uses sum by (<label>) and returns the 50 largest values. A LogQL `| regexp` stage with a named capture can supply the label, for example `| regexp "task=(?P<task>\\d+)"` with groupBy="task". groupBy="time" sends a range query with clock aligned steps; groupBy="<label>,time" gives one row per label value and time bucket. Totals, label counts and combined label/time buckets allow one day by default; time-only buckets allow seven days. The optional step is an integer duration from 1s through 1d, such as step="1d"; without it, the step is chosen from 1 second through 1 day at least window/12. A 1d step is a fixed 24-hour bucket aligned to UTC, not a calendar day in the connection timezone. At most 200 time buckets are allowed. A combined result shows the 50 largest label values and at most 200 rows, with an explicit cut marker; the header total includes hidden values and rows. Each row is a bucket start; when rows cross midnight in the connection timezone, a `--- YYYY-MM-DD ---` marker precedes the first row of the new day. Edge buckets can extend beyond the requested window; the header names the aligned interval. Counts describe Loki matches, not processed lines. The tool does not mark spikes or infer a cause. A count may still time out; retry a one-day subwindow or narrower LogQL query, and split further if needed.

## exportLogs(connection, query, start, end, format, directory)

The only write operation. It reads the window forward in pages and writes matching lines in full, oldest first, to one new local file in the requested directory. Omitting format uses the connection's configured exportFormat template when one exists, and raw otherwise. format="raw" preserves the line returned by Loki after the LogQL pipeline; a query with `| line_format` therefore writes the reformatted line. A template passed as format renders fields locally; an unrecognised plain line is written unchanged unless the template includes {line}. The answer names the effective format. Existing files are never overwritten.

Without directory, the default is <data dir>/exports. A relative directory resolves under that default, while an absolute directory is used directly. By default, the only restriction is the process's filesystem permissions; configured exportRoots, if present, restrict destinations after normalization and symbolic link checks. An empty result creates no file. maxExportLines, maxExportBytes and maxExportDurationMs stop a call and the response gives a start to continue in another file; lines of the last millisecond will repeat. The total duration includes all page requests; each request is capped by the smaller of requestTimeoutMs and the remaining export duration. A Loki or disk error after at least one line keeps the partial file and says where it stopped. If the duration expires before any line is written, the tool returns OPERATION_TIMEOUT without a file.

Paging cannot pass through a nanosecond that contains more lines than Loki returns in one page. The report explicitly says that lines of that nanosecond may be missing; in this case it does not claim a complete export. The answer is the path, counts and status, never the file's contents.

The format template syntax and directory settings are in [connections.md](connections.md).

## Budget and errors

The rendered text budget is maxResponseBytes minus 512 bytes reserved for the JSON-RPC envelope. queryLogs drops lines from the opposite end of the requested order; time bucket output drops trailing rows; discoverLogs drops values or complete label sets from the end of a sorted list. If even a minimal response cannot fit, the service returns Error RESPONSE_BUDGET_EXCEEDED.

Failures return Error <CODE>: <message> with isError=true. Spring AI builds the error result from the exception and currently repeats the text on a second line. Missing or mistyped arguments are rejected by the MCP SDK input validation as Tool (<name>) input validation failed: …; other invalid local arguments are INVALID_ARGUMENT. Loki HTTP 400 query text and a status:error query message are passed to the model, bounded to 400 characters, so it can correct its LogQL. Other upstream bodies and connection secrets are hidden. Transport codes and timeouts are in [http-client.md](http-client.md); an export-wide deadline before the first line returns OPERATION_TIMEOUT.

## Verification

The regular build and test use mock or loopback Loki. StdioSmokeTest starts the packaged jar as a separate process and checks the five tool schemas, text results, budgets, errors, export and clean stdout. integrationTest uses pinned Loki 2.6.1 and 3.6.0 containers. The read only live check is scripts/live_smoke/run_smoke.py.
