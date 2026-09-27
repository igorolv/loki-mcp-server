# Query, count and export contract

Every tool returns one readable text content, without an output schema or structuredContent. All data tools require an explicit connection from listConnections. queryLogs, countLogs and exportLogs require a LogQL **log query** starting with a stream selector. The same query can be passed to all three tools; metric expressions belong inside countLogs and are built by the server. The server never builds a query from log contents.

start defaults to now-1h and end to now. Accepted times: now, now-15m (units ns/ms/s/m/h/d), RFC3339 with an offset, local time in the connection timezone, or epoch nanoseconds. maxIntervalSeconds bounds the window. Time is kept in nanoseconds internally and printed in the connection timezone with milliseconds.

## queryLogs(connection, query, start, end, limit = 50, raw = false)

One backward Loki query_range request reads at most limit lines, capped by maxEntries. The page is rendered oldest to newest. The default view displays a local time, level, service, message and compact stack trace. The date is in the header and day changes inside a page have markers.

~~~text
{app="backend"} |= "ERROR" — dev, 2026-09-13 10:00:00–11:00:00 (+03:00), all 2 lines:
10:12:03.123 ERROR backend  Connection refused
10:15:08.456 ERROR backend  Request failed
Shown all 2 matching lines.
~~~

A message is cut at 400 code points; stack traces keep a few frames and show the number skipped. raw=true displays the line returned by Loki with the query result's stream labels, cut at 4000 code points. It is a preview and can cut JSON. exportLogs writes complete returned lines.

When Loki returns exactly limit lines, the header says “newest N lines (more may exist)”; this does not claim that another line exists. The footer gives an end value rounded up to the next millisecond. Loki treats end as exclusive, so the boundary is reread: duplicates are possible. If more than a page of lines share one timestamp, repeating the same end may return the same page; narrow the selector or filter. A response budget cut drops oldest displayed lines and says “Output limit reached”. An empty page suggests widening the window, using discoverLogs or simplifying the filter.

## countLogs(connection, query, start, end, groupBy)

Without groupBy, the server sends sum(count_over_time(<query> [<window>])) as an instant query at end and returns one count. groupBy="<label>" uses sum by (<label>) and returns the 50 largest values. groupBy="time" sends a range query with clock aligned steps chosen from 1 second through 1 day, at least window/12. Each row is a bucket start. Edge buckets can extend beyond the requested window; the header names the aligned interval. Counts describe Loki matches, not processed lines. The tool does not mark spikes or infer a cause.

## exportLogs(connection, query, start, end, format = raw, directory)

The only write operation. It reads the window forward in pages and writes matching lines in full, oldest first, to one new local file under exportRoots. format="raw" preserves the line returned by Loki after the LogQL pipeline; a query with `| line_format` therefore writes the reformatted line. A template passed as format renders fields locally; an unrecognised plain line is written unchanged unless the template includes {line}. Existing files are never overwritten.

A relative directory is resolved under the default export root. An absolute directory must be inside an allowed root after normalization and symbolic link checks. An empty result creates no file. maxExportLines and maxExportBytes stop a call and the response gives a start to continue in another file; lines of the last millisecond will repeat. A Loki or disk error after at least one line keeps the partial file and says where it stopped.

Paging cannot pass through a nanosecond that contains more lines than Loki returns in one page. The report explicitly says that lines of that nanosecond may be missing; in this case it does not claim a complete export. The answer is the path, counts and status, never the file's contents.

The format template syntax and directory settings are in [connections.md](connections.md).

## Budget and errors

The rendered text budget is maxResponseBytes minus 512 bytes reserved for the JSON-RPC envelope. queryLogs drops oldest displayed lines; discoverLogs drops values from the end of a sorted list. If even a minimal response cannot fit, the service returns Error RESPONSE_BUDGET_EXCEEDED.

Failures return Error <CODE>: <message> with isError=true. Spring AI builds the error result from the exception and currently repeats the text on a second line. Missing or mistyped arguments are rejected by the MCP SDK input validation as Tool (<name>) input validation failed: …; other invalid local arguments are INVALID_ARGUMENT. Loki HTTP 400 query text and a status:error query message are passed to the model, bounded to 400 characters, so it can correct its LogQL. Other upstream bodies and connection secrets are hidden. Transport codes and timeouts are in [http-client.md](http-client.md).

## Verification

The regular build and test use mock or loopback Loki. StdioSmokeTest starts the packaged jar as a separate process and checks the five tool schemas, text results, budgets, errors, export and clean stdout. integrationTest uses pinned Loki 2.6.1 and 3.6.0 containers. The read only live check is scripts/live_smoke/run_smoke.py.
