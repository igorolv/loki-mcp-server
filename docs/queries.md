# Reading logs, counting, summaries and metrics

Every tool returns a single text `content`. There are no output schemas, no
`structuredContent`, no cursors and no field projections. The consumer is a small model
(target: DeepSeek 4.1 Flash) that sees only the tool descriptions, the server
`instructions` and the response text.

Common parameters: `connection` is required (a name from `listConnections`); `start`
defaults to `now-1h`, `end` to `now`. Time formats: `now`, `now-15m` (`ns/ms/s/m/h/d`, the
short form `15m` is accepted), RFC3339 with an offset, local time in the connection's
timezone, or epoch nanoseconds. The window is limited by the connection's
`maxIntervalSeconds`.

## queryLogs(connection, query, start, end, limit = 50, raw = false)

One backward `query_range` request with `limit` (at most `maxEntries`). Lines are printed
in chronological order:

```
{app="backend"} |= "ERROR" — dev, 2026-09-13 10:00:00–11:00:00 (+03:00), newest 50 of more:
10:12:03.123 ERROR backend  Connection refused to nsi-backend:8080 [trace=4f2a1b3c4d5e6f70…]
    java.net.ConnectException: Connection refused
    at java.base/sun.nio.ch.Net.connect0(Native Method)
    ... (37 frames skipped)
    Caused by: java.io.IOException: inner
    at x.Y.z(Y.java:9)
Shown 50 newest lines; oldest shown 2026-09-13T10:12:03.123+03:00. Older: repeat with end="2026-09-13T10:12:03.124+03:00". Too many lines? Narrow the query (add a filter or level) or use countLogs / summarizeLogs.
```

A line is `HH:mm:ss.SSS LEVEL service  message [trace=…]` in the connection's timezone.
The date is in the header; when the day changes inside a page a `--- yyyy-MM-dd ---`
marker is inserted. Field extraction rules are in
[discovery.md](discovery.md#line-normalization). A missing value is printed as `-`; the
line is never hidden.

Stack traces (`error.stack_trace`, `stack_trace`, `stacktrace`, `exception`, or multi-line
plain text with `at `): the first exception line, up to 5 frames, `... (N frames skipped)`,
every `Caused by:`/`Suppressed:` with one frame; `... N more` lines are dropped. The message
is cut at 400 code points with `…`; line breaks become spaces. `raw=true` prints
`HH:mm:ss.SSS {stream labels}  <Loki line>` without any parsing, limited to 4 000 code
points — this is the way to the full original line. Labels are printed alphabetically as
`{app="x", pod="y"}`: in "show everything" mode the model sees pod/instance, which the
normal mode hides behind the service name.

Header: `newest N of more:` when Loki returned exactly `limit` lines, `all N lines:` when
fewer, `no matching lines.` when none. Footer: `Shown all N matching lines.`, or a hint with
a ready-made `end`. That `end` is the oldest shown timestamp rounded **up** to the next
millisecond: Loki treats `end` as exclusive, so the boundary is re-read (one duplicate is
possible) but lines with the same millisecond are never lost. Nothing is stored between
calls; no snapshot is promised. An empty result suggests widening the window, checking
labels with `discoverLogs` or simplifying the filter.

## followKey(connection, selector, key, start, end, limit = 100)

Every line of a stream selector that holds one identifier, oldest first, across services:
what happened to one task, request or message on a stand without tracing. `key` is
`name=value` or `name: value` as `summarizeLogs` prints it (`taskExecutionId=13548`), or a
bare value (`ERR-5ced1eb2-849d-478d-8cbe-31ad23a1f88a`); the value must be 3–200
characters without quotes or backslashes. `selector` is a plain stream selector covering
every service to search (`{namespace="dev"}`); the server adds the filter itself:
one forward `query_range` of `selector |= "value"`, `limit` lines. The value is then matched
as a whole token (`13548` is not found in `135480` or `ERR-13548x`); lines that held it only
inside a longer word are counted in the header and left out.

```
Lines with taskExecutionId=13548 in {namespace=~"dev|asv-dev"} — dev, 2026-09-22 00:29:32–2026-09-23 00:29:32 (+03:00): all 12 lines in 2 services (scheduler-main, ssj-main); first error 00:00:13.689 ssj-main. 6 lines holding 13548 only inside a longer word were left out.
00:00:13.293 INFO  scheduler-main  [TASK_DB_CREATE] Создана запись о выполнении: taskExecutionId=13548, taskName=ssd-load, …
00:00:13.665 INFO  scheduler-main  [TASK_DISPATCH] Отправка задачи на выполнение: taskExecutionId=13548, taskClass=…UploadInsuranceCompany, worker=ssj-service, …
00:00:13.682 INFO  ssj-main  [TASK_ACCEPT] Принята задача (sync): taskExecutionId=13548, …
00:00:13.689 ERROR ssj-main  [TASK_EXECUTE_ERROR] Ошибка при выполнении задачи: taskExecutionId=13548, …
         SpectrumException: Не найдена доступная задача с классом ru.it_spectrum.asv.ssj.bc.tasks.UploadInsuranceCompany
         at ru.it_spectrum.asv.bc.task.runtime.TaskServiceImpl.findDelegate(TaskServiceImpl.java:321)
         [configuration: task worker] The service the scheduler sent the task to has no worker for …
00:00:13.762 ERROR ssj-main  [TASK_EXECUTION_ERROR] Ошибка при выполнении задачи: taskExecutionId=13548
         SpectrumException: …  ← wrapped in ExecutionException, SpectrumException
         at ru.it_spectrum.asv.bc.task.runtime.TaskServiceImpl.findDelegate(TaskServiceImpl.java:321)
         [configuration: task worker]
00:00:13.777 WARN  scheduler-main  [TASK_FAILED_STATUS] Задача вернула ошибочный статус: taskExecutionId=13548, status=FAILED, …
         [configuration: task worker]
Shown every line of the window with this key.
```

The header names the services in the order they appear and the first error (a line with
level `ERROR`/`FATAL` or a stack trace). A line prints as in `queryLogs` (message cut at
400 characters) followed by the root cause, the application frame and the rule tag as in
`summarizeLogs`; the advice of a rule is printed at its first line only. Lines are
chronological with date markers; the budget cuts the newest lines, since the story of a key
starts at its first one: `Output limit reached: showing the oldest N of M lines.` When
lines were cut or `limit` was reached, the footer says `Newer: repeat with start="<time of
the last shown line>"` — the boundary millisecond is read again rather than lost. An empty
result advises a window around the time the key was seen or a wider selector.

## getLogContext(connection, selector, time, before = 20, after = 20)

Lines of one stream selector around a moment: `before` lines up to and including it and
`after` lines past it. Context is counted in lines, not time, so it does not depend on how
busy the stream is. Two `query_range` requests: backward with `end` = end of the moment and
`limit = before + 1` (one line is the target itself), forward with `start` = end of the
moment and `limit = after`. Each request's window is the connection's `maxIntervalSeconds`
in that direction.

`selector` must be a stream selector only (the same check as in `discoverLogs`); `|=` and
`| json` are rejected with an explanation: stack trace continuation lines that do not
contain the filtered text must stay visible. `time` accepts every `start`/`end` format plus
a bare time of day from the page — `10:12:03.123`, `10:12:03`, `10:12` — resolved in the
connection's timezone to the nearest such moment in the past (today, otherwise yesterday).
The moment has the precision of the text: `10:12:03.123` covers one millisecond,
`10:12:03` one second, RFC3339 without a fraction one second, epoch nanoseconds one
nanosecond. Lines inside the moment are marked with `>>>`; when there are none, the line
`>>> (no line at exactly this time in {...}; lines before and after it follow)` takes their
place and the spare slot is not shown as an extra "before" line.

```
Context in {app="backend"} around 2026-09-13 10:12:03.123 (+03:00) — dev, lines: 20 before, 1 at that time, 20 after:
10:11:58.001 INFO  backend  Handling request [trace=4f2a1b3c4d5e6f70…]
...
>>> 10:12:03.123 ERROR backend  Connection refused to nsi-backend:8080 [trace=4f2a1b3c4d5e6f70…]
    java.net.ConnectException: Connection refused
10:12:03.130 -     backend  	at java.base/sun.nio.ch.Net.connect0(Native Method)
...
Earlier: repeat with time="2026-09-13T10:11:58.001+03:00", after=0. Later: repeat with time="2026-09-13T10:12:09.870+03:00", before=0. Full original line: queryLogs with raw=true and a narrow filter.
```

Footer: when fewer lines than asked were found on a side — `No earlier/later lines within
24h ...` (the reach of the request, not proof that nothing exists); otherwise ready-made
`time` values to continue in each direction. When all `before + 1` lines fall inside the
moment (second precision in a busy service) the advice is to pass the time with
milliseconds; when they really share one millisecond (a plain-text stack trace printed line
by line) the advice is a larger `before`. Under the budget lines are dropped from the longer
side and the marked lines are never dropped: `Output limit reached: showing N before and
M after of K fetched lines.`

## summarizeLogs(connection, query, start, end, sample = 500)

A summary instead of reading: the `sample` newest lines of the window, grouped locally —
Loki's pattern API is not used, so it works on 2.6.1 too.

**Grouping.** A line with a stack trace (`error.stack_trace` and the other stack fields
of a JSON line, or the frames after the first line of a plain one) is grouped by its root
cause: the last `Caused by:` of the trace (the first section of a root-first `Wrapped by:`
trace; `Suppressed:` never counts), the first line of its message with identifiers
replaced by `*`, and the application frame — the first frame under the root section whose
class starts with one of the connection's `applicationPackages`, else the first such frame
of the wrapper nearest to the root. Servlet filter methods (`doFilter`,
`doFilterInternal`) are never the application frame; lambda and CGLIB decorations are
removed (`lambda$findDelegate$1` → `findDelegate`, `Service$$SpringCGLIB$$0` → `Service`)
and the line number is not part of the key. The wrappers are shown, not grouped by, so the
lines one failure produces through different wrappers fall into one group. Any other line
is grouped by its message template: UUIDs, dates and times, hex identifiers and numbers
(including ones with a unit — `15ms`, `42MB` — and space-grouped thousands — `6 029`;
`v1.2` and `asva2` are kept) replaced by `*`. Frame lines (`at ...`, `... N more`) of
services that log stack traces one line per frame collapse into one group with an
explanation instead of an example.

```
Summary of {namespace="dev"} |= "ERROR" — dev, 2026-09-21 00:00:00–21:00:00 (+03:00): newest 500 lines sampled (more exist), spanning 08:15:06.757–20:33:46.059, 33 distinct messages.
Groups by count in the sample (first–last time, level, service, newest example):
    2×  19:00:09.753–19:00:09.846  ERROR nsi-backend  [TASK_EXECUTION_ERROR] Ошибка при выполнении задачи: taskExecutionId=500004
         NullPointerException: Cannot invoke "String.contains(java.lang.CharSequence)" because "filePath" is null  ← wrapped in ExecutionException, SpectrumException
         at ru.it_spectrum.asv.nsi.tasks.UploadInsuranceCompanyTask.getFile(UploadInsuranceCompanyTask.java:197)
    2×  09:15:48.800–09:15:49.684  ERROR sec-ui-backend  ErrorID: ERR-… | Path: /api/systemGrid/getByCurrentUser | Exception: org.springframework.http.converter.HttpMessageNotWritableException
         IOException: Обрыв канала  ← wrapped in HttpMessageNotWritableException, …, AsyncRequestNotUsableException, ClientAbortException
    1×  20:14:45.483  ERROR sbp-ui-backend  Schema "sbp" has version 1.7, but no migration could be resolved in the configured locations !
Rare (1–2 lines each, easy to miss):
  …
Counts are for the 500 sampled lines only; countLogs gives the number for the whole window. To read one group: queryLogs with |= "<distinctive part of its message>".
```

**Rules.** When the connection has a rules catalogue (`rulesFile`,
[connections.md](connections.md#rules-catalogue)), every line is matched against it and a
group takes the match of its lines. A matched group prints one more line,
`[category: subject] advice`. Above the groups, `Known causes by the rules of this
connection` sums the matched lines by category and subject (up to 10 entries). Noise groups
are not listed among the groups: a block `Noise by the rules of this connection (N of M
sampled lines, not listed above)` prints one line each (count, span, service, rule id, the
root cause or message cut at 120 characters; up to 10 groups). When the sample was cut and
noise holds at least a third of it, the block ends with the filters of the noise rules that
hold at least a tenth of the sample — `Noise takes 475 of 500 sampled lines; to sample past
it, add != "NoResourceFoundException" to the query.` Under the budget noise groups are
dropped after the rare ones and before the top list. Without a catalogue the output is as
below.

**Linked keys.** Every line's identifiers are collected: `name=value` or `name: value`
pairs of the message whose name ends in `Id`, `ID` or `_id` (the value 3–64 letters,
digits, `_` or `-`), and the trace id. When a key of a group's newest line is carried by
lines of other groups, the group prints `linked: taskExecutionId=13548 → scheduler-main 2
lines` (up to two keys, counted by the service of those groups) and the footer adds
`Groups with the same 'linked:' key are one failure seen by several services; followKey
with that key shows its lines in order.` A key carried by one line only (the `ErrorID` of a
single request) is not printed.

A group prints its newest line (level, service, the message cut at 200 characters), then
for a root-cause group the root type, its message when the logged message does not already
contain it (cut at 160 characters) and the wrappers outermost first — repeats collapsed, a
chain longer than three shown as the outermost, `…` and the two nearest to the root — and
the application frame with its line number. A group without a root cause prints up to three
exception headers of its stack trace instead.

Groups are sorted by count in the sample, then by recency; the first 20 are shown. Groups
of 1–2 lines outside the top list are printed separately (up to 20, newest first) — a rare,
different error must not disappear. The rest is counted in `(+N more groups, M lines)`.
A single-line group prints one time. The header names the real span of the sample
(`spanning`): 500 newest lines may cover only part of the window, so the frequency in the
sample is never presented as a statistic of the interval — the footer points to
`countLogs`. Under the budget rare groups are dropped first, then the groups of a day
earlier that are gone, then noise groups, then restarted services (the dropped ones go into the `(+N more services: …)` line), then groups
from the end of the list: `Output limit reached: showing N of M groups.` An empty result gives the same
advice as `queryLogs`.

**Reading the sample.** An error line of a Spring Boot ECS service is 16 KB on average
(up to 30 KB), so the sample is read backwards in pages: the first asks for 50 lines, the
next ones for as many as fit half of `maxHttpResponseBytes` at the average line length seen
so far. Each next page ends 1 ns after the oldest line read, because Loki's `end` is
exclusive; the lines of that instant come back and are dropped, and the page asks for that
many more, so an instant holding more lines than a page is read through. Reading stops at
`sample` lines, at the start of the window, or when the lines read reach
`maxHttpResponseBytes`; the header then says `newest N lines sampled (more exist; stopped
at X MB of log text)`.

**Restarts and deploys.** After the sample the summary asks Loki once more for the start
and stop lines of the same streams in the same window: the query's stream selector without
level matchers (`level`, `detected_level`, `severity`, `lvl` — start lines are INFO) plus

```
|~ "Start|Graceful shutdown complete" |~ `Started \S+ in \S+ seconds|Starting \S+ (v\S+ )?using Java|Graceful shutdown complete`
```

The first stage is a literal alternation that Loki runs as a substring search: over a day
of the asva2 DEV stand on Loki 2.6.1 it takes 4–5 s, the regular expression alone 18 s. The
request asks for `maxEntries` newest lines; the lines are parsed in Java (message of a JSON
line, or the plain line). A start is a `Starting X … using Java` / `Started X in N seconds`
pair in one stream — a restarted pod is a new stream; a `Started` line without its
`Starting` begins at `Started` minus the printed process uptime; a `Starting` without
`Started` is an unfinished start. `Graceful shutdown complete` is a stop. The service is the
line's own `service.name` plus, when it differs, the service label of the stream
(`sbp-ui-backend [sbp-main]`: one helm release holds several services). The version is
ECS `service.version`, `build.version` and `git.commit` (10 characters, `unknown`
skipped), else the `v2.4.1` of the `Starting` line. A deploy is a start whose version differs
from the previous start of the service or, for its first start in the window, from the stop
of another stream of that service within 10 minutes (the pod it replaced: in a rolling
update the old pod stops a few seconds after the new one started, and its stop line carries
the old version).

One line per service, at most 10: services whose starts contain sampled lines first, then
those with deploys, unfinished starts or stops without a start nearby, then the most
recent. Up to four start times (the time of `Started`), `version … , unchanged` when every
start printed the same version, or the deploys (the last two, only the changed parts) and
the current version. A group whose lines were logged by a service while it was starting —
same stream, between `Starting` and `Started`, or after an unfinished `Starting` — says so
under its example:

```
Restarts and deploys in the window (Spring Boot start and graceful stop lines of {namespace="dev", app=~"asv-app|sp-app"}):
  sbp-ui-backend [sbp-main]  started 13:08:13.827, 15:57:38.749, 16:56:42.055; version development build LOCAL, unchanged; 3 sampled lines logged while starting
  ssj-backend [ssj-main]  started 12:28:19.155, 13:36:53.053, 16:18:28.254, 17:21:23.368; 4 deploys, last 2 at 16:18:28.254 build 2790 → 2791, commit c000000009 → c000000007; 17:21:23.368 build 2791 → 2792, commit c000000007 → c000000008; now main build 2792 commit c000000008
  sbp-backend [sbp-main]  started 13:06:46.451, 15:56:59.452, 17:02:33.157; version development build LOCAL, unchanged; start at 17:00:01.481 did not finish (no "Started" line after it in its stream)
  (+30 more services: ssj-ui-backend [ssj-pr-1374], …)
Groups by count in the sample (first–last time, level, service, newest example):
    3×  13:06:32.721–16:55:09.062  ERROR sbp-main  Schema "sbp" has version 1.7, but no migration could be resolved in the configured locations !
         logged while starting: 3 of 3 lines, newest in the start of sbp-ui-backend [sbp-main] 16:54:24.863–16:56:42.055, version development build LOCAL
```

No block is printed when the window has no such lines or the query has no stream selector
left to reuse. A failed request costs the block, not the summary: `Restarts and deploys: not
checked, the query for start and stop lines of {…} failed (UPSTREAM_TIMEOUT).` Only Spring
Boot / Tomcat / Netty lines are recognised; other stacks show no block.

**History of the groups.** Every printed group (top and rare, not noise) is looked up in
the 7 days before the window by counting, not by reading lines. The count is of lines of
the query that hold a fragment of the group's text: the longest part of the first line of
its root message (or of its message, for a group without a root cause) between the parts
replaced by `*`, at least 8 characters, else the root type's simple name; JSON-escaped for
a JSON line, because Loki searches the raw line; cut to 60 characters at a word boundary.
Frame-line groups and messages without such a part are not looked up.

One metric request counts many groups. The query's stream selector gets a matcher on the
service label when every group took its service from that label (`instance=~"nsi-main|
sbp-main|…"`), the query's pipeline is kept, and two stages are added: a literal
alternation of the fragments, longest first, which Loki runs as a substring search, and the
same alternation as the named group of `| regexp`, so that the fragment a line holds becomes
the label `mcp_fragment`; the sum is by that label and the service label. A group adds up
the series of its fragment and its services. A line holding two fragments counts once, for
the one that starts first. Groups go into one request until the URL-encoded query reaches
6000 bytes (an ingress passes 8 KB request lines; a Cyrillic character is 6 bytes encoded),
then into the next. Each evaluation is moved by `offset` so that it ends where it should,
because Loki aligns the steps of a range query to multiples of the step (UTC midnight):

- `sum by (mcp_fragment, instance) (count_over_time(<scope> [86400s] offset Xs))`, step one
  day — the seven periods of 24 hours before the window start. All zeros: `new: not seen in
  the 7 days before`.
- `… [<window>s] offset Ys))`, step one day — for a window of at most 6 hours the window
  itself and the same hours on each of the 7 days before: `more than usual: 6 in this
  window, usually 0 at these hours; 14 in the 7 days before` when the window holds at least
  three times the median of the same hours (at least 1) and the Poisson tail `P(X ≥ window |
  λ = max(median, 0.5))` is below 0.01, otherwise `seen before: 22 in the 7 days before,
  usually 0 at these hours`. A longer window is compared with the median of the daily
  counts scaled to its length (`usually about 10 in 12h`); its own count is the group's count
  in the sample when the sample read the whole window, else the request asks for the window
  only. The same hours of a day window would read 8 days of lines: 27 s on the asva2 DEV
  Loki 2.6.1.
- Groups never seen before need no second request.

The line is printed under the group; above the groups one line sums them up: `Compared
with the 7 days before (lines of this query with the same text): 4 groups new, 2 more than
usual, 7 seen before.` The requests run one after another — four in parallel tripped the DEV
Loki's rate limit (HTTP 429) and left its queue full for the next call — and no request
starts once the history has taken 20 seconds. A group without its 7 days says `history not
checked`, and the summary line adds `; N not checked (Loki did not answer in time or refused
the count)`; without its same hours it says `seen before: N in the 7 days before`. A
fragment is a heuristic: two lines of one failure can come out differently when one of them
carries text that earlier lines of the failure did not. A stand whose retention is shorter
than 7 days makes old groups look new. In the server's own log these requests show the
fragments as `<log text>`. On the DEV stand a summary of 4 hours took 8 s with the history,
of 24 hours 34 s (16 s of them the sample itself).

**Gone since a day earlier.** When the sample read every line of a window of at most 24
hours, the same query is read over the same hours a day earlier (one page of 50 lines,
read the same byte-aware way) and grouped; up to 5 groups that are not in this window and
are not noise are listed after the groups:

```
Seen at these hours a day earlier, not now (in a sample of 25 lines of 2026-09-23 09:06:56–13:06:56 (+03:00)):
    2×  scheduler-main  SocketTimeoutException: Connect timed out
```

The block tells a day that was not normal from one that was; with a cut sample it is not
printed, because a group missing from the sample may still be in the window. A failed read
prints `Seen at these hours a day earlier, not now: not checked (Loki did not answer in
time or failed).`

## countLogs(connection, query, start, end, groupBy)

The server builds the metric LogQL; `query` is an ordinary log query starting with `{`.

- Without `groupBy`: an instant query `sum(count_over_time(<query> [<window>]))` at `end`:
  `1 523 lines match {app="backend"} |= "ERROR" in 2026-09-13 10:00:00–11:00:00 (+03:00) (dev).`
- `groupBy="<label>"`: `sum by (<label>) (count_over_time(...))`, a table in descending
  order, up to 50 values, an empty label prints as `(none)`.
- `groupBy="time"`: a range query with a "nice" step not smaller than `window/12`
  (1s … 1d: 1m, 2m, 5m, 15m, 30m, 1h, 2h…), buckets aligned to the clock — evaluations are
  at multiples of the step from the epoch, the way Loki itself aligns them when splitting by
  interval (asking for other moments produced doubled buckets on a live stand). The edge
  buckets may extend past the window, so the header names the aligned interval. Each row is
  a bucket start. `<- spike` marks a bucket that is ≥ 5 and ≥ 3 × the median bucket (3 × the
  mean when the median is zero). This is a simple rule, not analysis.

A metric expression (`sum(...)`, `rate(...)`) is rejected with the advice to use `queryMetrics`.

## queryMetrics(connection, query, start, end, step)

Always a range query. `step` is `30s`, `5m`, `1h`; the default is the nearest "nice" step
not smaller than `window/20` (1s … 1d). The number of evaluations per series must not
exceed `maxMetricPoints`.

```
sum by (level) (rate({app="backend"}[5m])) — dev, 2026-09-13 10:00:00–11:00:00 (+03:00), step 5m, 2 series:
{level="error"}
  09-13 10:00  0.5
  09-13 10:05  0.7
{level="warn"}
  09-13 10:00  0.1
Output trimmed to 2 series / 40 points (connection limits). Aggregate with sum by (...) or use a larger step.
```

Values are printed as Loki strings (`NaN`, `+Inf` are kept). Point times are `HH:mm:ss` for
steps under a minute, otherwise `MM-dd HH:mm`. A log query is rejected with the advice to
use `queryLogs`/`countLogs`; there is no instant mode.

## Errors

An error is the text `Error <CODE>: <what is wrong and what to do>` with `isError=true`.
HTTP 400 and `status=error` from Loki are passed on as `Loki rejected the query: <Loki
text>` (up to 400 characters, control characters removed): the model fixes its LogQL from
it. Other statuses (401/403/404/429/5xx) and upstream texts stay hidden. Argument errors may
repeat the model's argument (an unparseable time, for example) but never credentials, URLs
or texts of other statuses.

## Size limit

The connection's `maxResponseBytes` minus 512 bytes for the JSON-RPC envelope is the text
budget. `queryLogs` drops the oldest lines until the page fits and writes `Output limit
reached: showing N newest of M fetched lines.`; when not even one line fits —
`Error RESPONSE_BUDGET_EXCEEDED`. `discoverLogs` first shortens the example, then removes
it. The `QueryToolsConfig` wrapper checks the actual text size as the last guard.

## Verification

`gradlew.bat build --console=plain` — unit tests for the line format, stack trace
compaction, footer, budget, countLogs/queryMetrics, getLogContext (two requests, marker,
time precision, trimming around the target), summarizeLogs (templates, rare groups, budget;
the history and the gone groups against the counts DEV Loki gave for
`asva2-dev-contrast-*`, `GroupHistoryTest`) and the stdio smoke on the packaged jar (`instructions`, tools/list without output schema,
16 outstanding calls with different budgets, errors without secrets, Loki 400 text).
`gradlew.bat integrationTest --console=plain` — Loki 2.6.1/3.6.0: page, continuation by
`end`, raw with labels, context (lines at the moment, exact time, a moment without lines,
pipeline rejection), count/groupBy/time, summary, metrics, parser error, discovery and label
values, and last the summary history against a line pushed a day before the window. `python scripts/live_smoke/run_smoke.py --connection <name>` — a read-only run of
the packaged jar over stdio against a live stand (see README, "Development").
