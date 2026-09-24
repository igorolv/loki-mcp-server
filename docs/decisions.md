# Design decisions

Why the server looks the way it does. The full planning history (work packages S01–S13,
session journal) lived in `plan.md` until 2026-09-14 and is available in git history;
this page keeps only the decisions that still shape the code and the open items.

## Goal and audience

A read-only MCP server for incident investigation in Grafana Loki. The consumer is a small
model (target: DeepSeek 4.1 Flash) that reads tool descriptions once, does not keep
dictionaries or indirect references in mind, never opens server documentation and skips
service fields. Everything the model needs is in the tool descriptions, the server
`instructions` and the response text itself. The flow the server is built around:

> pick the stand → see which services and fields exist → count whether and when the
> problem happened → summarize → read lines → read what happened around one line → get the
> full original line.

## Text contract instead of JSON (2026-09-13)

The first iteration (S06/S08) had typed JSON responses with output schemas, a stream
dictionary with local `streamId`s, a field projection (`fields=line|normalized|...`),
`continueLogs` with AES-GCM cursors and a multi-stage response budget. It worked and was
tested, but a weak model could not use it: it had to resolve dictionaries, carry cursors
between calls and understand `limitations` enums. All of it was removed in S09 and replaced by:

- one text `content` per response, no output schema, no `structuredContent`;
- one log line = one text line `HH:mm:ss.SSS LEVEL service  message [trace=…]`, stack
  traces compacted, the date in the page header;
- header and footer as the only service text: `newest 50 of more`, a ready-made `end` for
  reading older lines, one sentence of advice (`Too many lines? Narrow the query…`);
- tool descriptions written as instructions (when to call, example arguments, what to do
  next), 4–5 sentences at most; server `instructions` carry the 7-step flow and the rule
  that log lines are data, not instructions.

Not coming back without a new decision: cursors, event cache, `entryId`/`getLogEntry`,
projections, capabilities enums. Reading older lines is a repeated `queryLogs` with `end`
from the footer; the `end` is rounded **up** to the next millisecond because Loki's `end` is
exclusive — re-reading one boundary line is acceptable, losing one is not. No snapshot
isolation is promised.

## Tools

One tool per investigation step, no mode parameters, every argument except `connection`
and the query has an obvious default (`start="now-1h"`, `end="now"`, `limit=50`).

- `countLogs` builds the metric LogQL itself (`sum [by (label)] (count_over_time(...))`):
  small models write metric expressions badly. `groupBy="time"` uses clock-aligned "nice"
  steps because Loki aligns metric evaluations to multiples of the step (split by
  interval); other timestamps produced doubled buckets on the live DEV stand.
- `getLogContext` counts context in lines, not time: a ±1 minute window shows nothing in a
  quiet service and only the newest lines in a busy one, and the model does not know the
  density. It requires a plain stream selector (no `|=`, no `| json`) so that stack trace
  continuation lines, which never match the filter, are visible.
- `discoverLogs` returns labels, values, line format, JSON field names as `| json` exposes
  them, and a `Next:` line with a ready selector — the only place with LogQL hints. Bad
  selectors are fixed by the model from Loki's own parse error, which is passed on verbatim.
  `label="..."` lists all values of one label (taken from other Loki MCPs' `label_values`)
  because the overview cuts value lists at 10.
- `summarizeLogs` groups a sample locally by message template; Loki's pattern API is not
  used so that it works on 2.6.1. Counts are for the sample only and the text says so;
  rare 1–2 line groups are listed separately so that a one-off error is not lost;
  one-line-per-frame stack traces collapse into a single group.
- `queryMetrics` is range-only; `countLogs` covers the instant case.
- `raw=true` in `queryLogs` prints the original line with its stream labels — the only
  path to the full line and to pod/instance, which the normal mode hides behind the
  service name.

Not implemented on purpose: `loki_stats`, `loki_ready`, `loki_config` (nothing to
investigate with), `direction` (always the newest lines of the window), Grafana proxy
transport (Grafana adds no capability over direct Loki; revisit if a stand exposes Loki
only through Grafana), MCP prompts (deferred until the manual run with the target model
shows whether they help).

## Connections

Several stands in one process, configured in an external `connections.json`; every data
tool takes an explicit `connection` and there is no default even with one entry. The file
is loaded once, strictly, without probes; a bad file stops the start-up without echoing the
parser message or credentials. `${ENV}` placeholders are substituted once, without shell
evaluation. Per-connection `hint` (a short map of the stand for the model) and
`serviceLabels` (which labels name the service) replace project documents the model would
otherwise have to read; stand-specific label names and LogQL never go into the code.

## Honesty and budget

- Nanoseconds are kept internally; output is local time of the connection with
  milliseconds.
- Streams, lines and `totalLinesProcessed` are never confused; counts are line counts.
- `maxResponseBytes − 512` bytes of envelope is the text budget. `queryLogs` drops the
  oldest lines, `getLogContext` trims the longer side and never the marked lines,
  `summarizeLogs` drops rare groups first, `discoverLogs` shortens the example. The tool
  wrapper checks the actual size as the last guard. Every cut is one visible phrase
  (`Output limit reached…`, `… (N frames skipped)`, `…`).
- Loki 3.x `detected_level` (structured metadata) is a level; `unknown` is the absence of
  one. Loki 2.6.1 answers `{"status":"success"}` without `data` for empty `/series` and
  `/label/<name>/values` — decoded as an empty list.
- Errors are `Error <CODE>: what is wrong and what to do`, `isError=true`. Loki's HTTP 400
  text is passed on (it describes the model's own LogQL); other upstream texts, URLs,
  credentials and tenant are not.
- Diagnostics log one line per tool call and per Loki request (path, parameters trimmed to
  200 characters, status, size, time) with the connection name in MDC; never the host,
  auth, tenant, response bodies or log line contents.

## Stack

Java 21, Gradle Kotlin DSL with a version catalog, Spring Boot 4 and Spring AI MCP 2
(same as `jdbc-mcp-server` and `redmine-mcp-server`, which donated the build, connection
handling and stdio pattern). Stdio only; stdout belongs to JSON-RPC, logs go to stderr and
a rolling file. `immediateExecution(true)` and SDK input validation off, as in the donors.

## Verification

- `gradlew build`: unit tests plus a stdio smoke that runs the packaged jar as a separate
  process against a loopback mock Loki (no Docker, no network).
- `gradlew integrationTest`: Loki 2.6.1 and 3.6.0 in Testcontainers with deterministic
  events; the only place where anything is pushed to Loki.
- `scripts/live_smoke/run_smoke.py`: read-only run against a configured stand, selector
  taken from `discoverLogs`.
- Tool descriptions and `instructions` can only be verified by running the investigation
  scenario with the target model through a real MCP client; this is done manually.

## Observations from the asva2 stands (2026-09-14)

Recorded because they shaped defaults and hints, not as facts about the stands today.
DEV (Loki 2.6.1, k8s) exposes read paths only; recent streams may lack `applicationName`
(label present over 7 days but not in the last hours), so the DEV hint names `instance` /
`container` as fallbacks; the `level` label exists only on lines written after a service
restarted with the current logging config; non-JSON services log stack traces one line per
frame with no level — hence `getLogContext` without a `level` filter. TST (Loki 3.5,
docker) is almost silent, so an empty hour must lead to widening the window, not to "no
errors"; `detected_level` covers more lines than the `level` label; a 24-hour query with a
structured-metadata filter can exceed the 30 s request timeout.

## Analysis inside the server (2026-09-21)

The next direction: move the analysis a model would otherwise do by reading pages of lines
into Java, so that a tool answers an investigator's question (what fails, where in our
code, since when, because of which dependency, was there a restart) and prints evidence
pointers (time, selector, key) for drilling down with the existing tools. The principle
stays: no asva2 names in code; stand knowledge lives in the connection profile.

### What the asva2 DEV stand showed (2026-09-21)

Measured on 171 error lines of the Java services over 24 hours (`{namespace="dev",
app=~"asv-app|sp-app"} |~ "ERROR|Exception|Caused by"`); an anonymised sample is in
`src/test/resources/fixtures/asva2-dev-errors.jsonl` (one shortest line per group plus
the correlated lines of one task failure; UUIDs, user ids, business ids, git commits,
e-mails and hosts replaced with stable substitutes, so equal ids stay equal).

- The services write Spring Boot ECS JSON: `@timestamp`, `log.level`, `log.logger`,
  `process.thread.name`, `service.name`, `service.version` (`main`, `development`,
  `PR-1365`), `message`, `error.type`, `error.message`, `error.stack_trace`, MDC fields
  `applicationName`, `userId`, plus `build.version` and `git.commit`. `error.type` is the
  outermost exception; the root cause is the last `Caused by:` of the stack trace.
- An error line is 16 KB on average and up to 30 KB: 140–230 frames, 2–4 `Caused by:`
  sections, none shortened. 171 lines are 2.7 MB; 50 raw lines are a 800 KB page before
  compaction. Grouping the same lines by root cause and application frame gives 35 groups
  in 7.8 KB — the whole day's picture in one response.
- Root messages are multi-line (`PSQLException: … / Подробности: … / Позиция: …`,
  `ConstraintViolationException` with a list of violations). The application frame must be
  taken under the root cause section: the first application frame of the whole trace is a
  servlet filter (`SpRequestContextFilter.doFilter`) for every HTTP error.
- One failure produces several lines in several services with no trace id: `ssj-backend`
  logs `[TASK_EXECUTE_ERROR]` and `[TASK_EXECUTION_ERROR]`, `scheduler-backend` logs
  `[TASK_DB_FAILED]`, all within one second with the same `taskExecutionId=`. Other keys
  in message text: `ErrorID: ERR-<uuid>` and `Path: /api/…` from `GlobalExceptionHandler`,
  `fedExecCommId=`, `userId` in the JSON. There is no Micrometer tracing; `traceId` exists
  only in Kafka consumers.
- Noise is in Russian: `java.io.IOException: Обрыв канала` (broken pipe) behind
  `ClientAbortException`, `NoResourceFoundException` for a missing endpoint polled by a
  frontend (104 of 171 lines), `Invalid character found in the request target` from
  scanners. Numbers use a space as thousands separator (`ФОИВ 6 029`).
- Dependencies are named in root messages: `WebServiceTransportException: Service
  Temporarily Unavailable [503]` with the SMEV recipient in the wrapper, `PSQLException`,
  Flyway `Schema "sbp" has version 1.7, but no migration could be resolved` on three
  services within three minutes (a redeploy against a schema migrated by a newer branch).
- Not everything is ECS: `address` writes its own one-line format, `ais-service` (another
  repository) the plain Spring pattern. The plain branch of the normalizer stays.
- The DEV promtail had no `applicationName` / `level` labels on these streams: the JSON
  stage from `k8s/monitoring/loki-values.yaml` is not applied there. JSON parsing in the
  server does not depend on promtail, which is one more reason to do it here.

### Decisions

- **Root-cause signature.** A line with a stack trace is grouped by its root cause: the
  simple name of the last `Caused by:` type, the first line of its message normalized as
  today, and the application frame — the first `at` frame under the root section whose
  class starts with one of the connection's `applicationPackages` (else the first
  application frame of the trace, else none). Wrapper types are shown, not used for
  grouping. A line without a stack trace keeps the message template. Lines of one failure
  logged twice by the same service therefore fall into one group.
- **`applicationPackages` on the connection** (optional list of package prefixes). Empty
  means no application frame; nothing is guessed from class names.
- **Stack trace parser is our own.** It parses Java traces in the Logback/Log4j2 form
  (`Type: message` headers, `Caused by:`, `Suppressed:`, `... N more`, `... N common frames
  omitted`, tab or four-space indentation, `module/` prefixes, `Unknown Source`,
  `$$SpringCGLIB$$` proxies) and tolerates the logstash root-cause-first form
  (`Wrapped by:`). No library: nothing on Maven Central does this without dragging a
  platform along, and the format is stable.
- **Correlation keys from message text** (`taskExecutionId=`, `ErrorID:`, `Path:`,
  `fedExecCommId=`, `userId`) are extracted by generic `name=value` / `name: value`
  patterns and shown with a group; the names come from the line, not from code. A tool
  that collects every line of one key in a window replaces trace lookup on a stand without
  tracing.
- **Rules are data.** A catalogue per connection (`rulesFile`) maps a root cause or
  message pattern to a category (dependency, startup, configuration, noise), a subject and
  one sentence of advice. JSON, not YAML as first written: the same strict Jackson loading
  and safe errors as `connections.json`, no new dependency. The starting set is written from the sample above, in the language
  of the stand's messages; the code knows only the rule engine.
- **Byte-aware sampling.** 500 error lines × 16 KB exceed `maxHttpResponseBytes`; the
  summary sample is fetched in pages by moving `end` back and stops at `sample` lines or a
  byte budget, and the header says how many lines were actually read.
- **Deferred:** `compareLogs` (window vs. window, PR stand vs. main) — no confirmed use;
  `getTrace` — no tracing on the stands, the key-based collection covers Kafka `traceId`
  too; Drain-style template mining — `log.logger` plus the message template is enough for
  JSON logs.

### Work packages

- **S14 — stack trace parser and root-cause grouping** (done 2026-09-23: the first page is
  50 lines, not 200, because a 200-line first page of 30 KB lines is 6 MB before any size
  is known; the next page asks for the lines of the boundary instant on top, so that an
  instant with more lines than a page is read through). `StackTrace` (sections with type,
  multi-line message, frames, omitted count) and `ErrorSignature` (root type, root message,
  application frame, wrapper chain) in `service`; `applicationPackages` in
  `ConnectionDefinition` / `connections.md`; `EventNormalizer` learns `log.logger` /
  `logger_name`; `LogSummary` groups by signature and renders
  `N×  span  LEVEL service  RootType: root message`, then `at Class.method(File:line)` and
  `wrapped in Outer ← Middle`; number normalization accepts space-grouped digits; the
  sample is fetched in byte-aware pages; `docs/queries.md` and the tool description follow.
  Tests: parser cases from the fixture (multi-line PSQL root, constraint list, SMEV chain,
  no root cause, plain-text trace), grouping of the fixture into at most 35 groups under
  12 KB (the 7.8 KB of the prototype printed 90 characters a message; Russian text is two
  bytes a character; 13 KB after S15 added `linked:` lines), the two `ssj-backend` lines of one `taskExecutionId` in one group, the servlet
  filter never chosen as the frame, the existing `LogTextTest` / `StdioSmokeTest`
  unchanged.
- **S15 — correlation keys** (done 2026-09-23). `CorrelationKeys` takes `name=value` /
  `name: value` pairs whose name ends in `Id`, `ID` or `_id` and the trace id; a group of
  `summarizeLogs` prints `linked:` only for keys other groups carry, so a one-off `ErrorID`
  costs nothing. The eighth tool `followKey` reads one key across services oldest first
  (`|= "value"` in Loki, a whole-token match here) with the root cause and the rule tag of
  each error, the advice once per rule. On DEV `taskExecutionId=13548` gave the whole task
  in 12 lines of scheduler and ssj (created → dispatched → accepted → no worker → FAILED),
  6 lines holding the number inside longer numbers left out. The fixture summary grew to
  under 13 KB with the `linked:` lines.
- **S16 — rules catalogue** (done 2026-09-23). `rulesFile`, the engine (`LogRules`), a
  "Known causes" block and a "Noise" block in `summarizeLogs`, the asva2 starting set in
  `examples/asva2-rules.json`. `message` is also searched in wrapper messages: the URL of a
  failed neighbour and the SMEV recipient are there, not in the root cause. The filter hint
  names only noise rules holding a tenth of the sample, so the retried query stays short.
  On DEV on 2026-09-23, 475 of 500 sampled error lines were `NoResourceFoundException` for
  one path and covered 8 hours; with the suggested filter the same call read the whole day
  (71 lines).
- **S17 — restarts and deploys** (done 2026-09-23). A "Restarts and deploys in the window"
  block in `summarizeLogs` (no new tool) from one more Loki request over the query's stream
  selector without level matchers, `ServiceStarts` in `service`; a group whose lines a
  service logged while starting says `logged while starting: … in the start of <service>
  <begin>–<end>, version …`. What DEV showed (lines of 2026-09-22, anonymised in
  `src/test/resources/fixtures/asva2-dev-lifecycle.jsonl`):
  - Every restart is a new pod, so a new stream: "a change of version inside a stream"
    never happens. A start is paired inside its stream (`Starting X v… using Java` →
    `Started X in N seconds`); the deploy is found per service — against the previous start
    or the stop of the pod it replaced. In a rolling update the old pod logs `Graceful
    shutdown complete` 2–5 s after the new `Started`, with the old version in its JSON.
  - `main` and PR releases carry `build.version` (2789 → 2792 over a day) and `git.commit`;
    `development` releases print `build LOCAL` and `commit unknown`, so there a restart and
    a deploy cannot be told apart and the block says `version development build LOCAL,
    unchanged`.
  - The Flyway `Schema "sbp" has version 1.7 …` is an ERROR that `sbp-ui-backend` logs
    between `HikariPool-1 - Start completed` and `Started`, on every start (13:06, 15:55,
    16:55), and the start still finishes; the same for sms and parus. The summary now reads
    "logged while starting … sbp-ui-backend 16:54:24.863–16:56:42.055, version development
    build LOCAL". The live run also showed a `NullPointerException` in
    `VersionService.initVersionInfo` on every start of `ssj-ek-export-service`.
  - `HikariPool-N - Start completed` is not used: it repeats per pool, also after
    `Started` (`HikariPool-2` of parus), and adds nothing to the Starting/Started pair.
  - One `Starting` (a second component of `sbp-backend` in the same release) had no
    `Started` for the rest of the day; it is printed as an unfinished start.
  - Cost on Loki 2.6.1 over a day of `{namespace="dev", app=~"asv-app|sp-app"}`: the
    regular expression `Started \S+ in …|Starting …` alone took 18 s (over three days the
    ingress answered 502 after 2 minutes); a literal alternation `|~ "Start|Graceful shutdown
    complete"` in front of it, which Loki runs as a substring search, 4–5 s for 360 lines.
    The summary of the day's errors took 9–14 s in all.
  Later: the one-call incident overview built from S14–S17.

## Baseline and field contrast (2026-09-24)

Two questions an investigator asks of every group and the summary could not answer: is
this new or does it happen every day, and what do the failing lines have in common that
the other lines of the service do not (one pod, one build, one user). A prototype over the
S14 signatures ran on the DEV stand on 2026-09-24; the anonymised sample is in
`src/test/resources/fixtures/asva2-dev-contrast-*` (README there).

### What the prototype showed

The window was `{namespace="dev", app=~"asv-app|sp-app"} |~ "ERROR|Exception|Caused by"`
over 09:06–13:06 MSK: 67 lines in 13 groups.

- **One baseline window is misleading.** Against the same hours of the day before, 12 of
  13 groups came out "new". That day had its own incident (`Connect timed out`, 570 lines
  in nine services; Redis `INFO` timeouts, 111), and the four hours before the window were
  night with 7 lines. A comparison needs several days, and the same hours of those days,
  because DEV is quiet at night and at the weekend (19–20.09 held zeros).
- **Counting beats downloading.** The 807 baseline lines were 15 MB and took 28 s to
  read. The history of a group needs a count, not lines: `sum(count_over_time(<query> |=
  "<fragment>" [4h] offset X))` narrowed to the group's `instance`, step `1d`, gives the
  same four hours on each of seven days in 0.5–1.2 s on Loki 2.6.1. Loki aligns the steps
  of a range query to multiples of the step (UTC midnight), so `offset X` (X = next
  midnight after the window end − window end) moves every evaluation to end at `end − k
  days`; an evaluation in the future is answered.
- **Same hours alone are not enough either.** `DeleteDraftUploads… не найдена доступная
  задача` was absent at those hours on every day but fails twice a day at other hours
  (a scheduled task). `[1d]` buckets ending at the window start (same offset trick) show it:
  0.5–1.4 s per group. The instant query `count_over_time(…[7d])` gives the same total in
  16–18 s: Loki 2.6.1 does not split instant queries by interval.
- **The fragment must be taken with the query's filters.** `|= "DeleteDraftUploads"` alone
  counted 22 lines in the window where the sample had 6 errors (INFO lines name the class
  too); with the query's own `|~ "ERROR|…"` in front the count equals the sample.
- **Result for the window** (the rules below applied to the fixture). New, not seen in the
  7 days before: 4 groups, among them `Загрузка файлов невозможна` (5 lines) and
  `Unrecognized schedule 'FIXED_DELAY|PT5M'`. More than usual: the task failure above (6
  now, 0 at these hours, 14 in the 7 days before) and `NoResourceFoundException
  /api/selectedDateTime` (35 against a median of 6 at these hours) — a statistic does not
  recognise noise, the rules catalogue still does. Seen before: 7 groups, e.g. Flyway
  `Schema … has version` (6 and 11 lines in 7 days), `inStream parameter is null`, Redis
  `Connection initialization timed out`. One disagreement: the scheduler's
  `[TASK_DB_FAILED] … taskName=Удаление ручных корректировок…` came out new while its
  sibling `[TASK_FAILED_STATUS]` line of the same failure was seen 22 times — its fragment
  holds the task name, which earlier lines of the failure did not carry in that form. A
  fragment is a heuristic, and the text calls the result "not seen", never "first time".
- **The "stopped" block works from a sample.** The groups of the day before that are gone
  (its `Connect timed out` burst) are what tells the model that the day before was not
  normal.
- **Fields of a group against the other lines of its service.** Sharp findings:
  `DeleteDraftUploads` — all 6 lines on pod `…-gbwck`, `build.version=2799`, one commit,
  against 6 % of the other `ssj-main` lines; `Загрузка файлов невозможна` — all 5 lines in
  thread `Информация по загрузке`, all from one `userId`. Noise to remove: the share in the
  background has to be taken among lines that carry the field (plain-text lines made every
  JSON field look rare); fields covering exactly the same lines (pod, build, commit, file
  name) are one finding; a field that the background hardly carries (`userId`) needs its own
  rule, "one value in every line of the group".
- **Background is cheap.** 12 slices of the window × 200 lines of the stream selector without
  filters took 0.2–0.4 s each. Quiet services get few lines that way (41 of
  `scheduler-main`), hence the minimum below.

### Decisions

- **Both blocks go into `summarizeLogs`, always, with no mode parameter.** They cost about
  5–20 s on top of the summary itself on DEV (measured in S18 below); a group whose history
  did not arrive within the call's time budget says `history not checked`. `compareLogs`
  stays deferred: this is not a comparison of two arbitrary windows.
- **History of a printed group, not of its lines.** Per group (top and rare, not the noise
  block) one fragment: the longest run of the first line of the root message (or template)
  between normalized parts, at least 8 characters, else the simple root type name; written
  as it appears in the raw line (JSON-escaped for a JSON line), cut to 60 characters at a
  word boundary. Counts are lines of the query holding the fragment, stated as such. (As
  first written each group had its own `|= "<fragment>"` requests on its own services; S18
  replaced that with one request for many groups, see below.)
- **Two requests for the groups.** First the seven `[1d]` periods before the window start;
  all zeros means `new: not seen in the 7 days before`. Then, for the groups seen before and
  a window of at most 6 hours, the same hours on the seven previous days. Wording:
  - `more than usual: 6 now, usually 0 at these hours, 14 in the 7 days before` when the
    count is at least three times the median of the seven same-hours counts (at least 1)
    and the Poisson tail `P(X ≥ now | λ = max(median, 0.5)) < 0.01`;
  - `seen before: 22 in the 7 days before, usually 0 at these hours` otherwise.
  A longer window is compared with the daily median scaled to its length (`usually about 10
  in 12h`). The header
  says `Compared with the 7 days before: 4 groups new, 2 more than usual, 7 seen before.`;
  a stand whose retention is shorter than 7 days makes old groups look new, which the
  header's period makes visible.
- **"Not now" block** for windows of at most 24 hours: one byte-aware sample page of the
  same query over the same hours a day earlier, grouped the same way; groups found there
  and absent now (and not noise rules) are listed, at most 5, under `Seen at these hours a
  day earlier, not now (in a sample of N lines of …)`. Printed only when the current sample read
  the whole window: otherwise a group missing from the sample may still be in the window.
- **Fields that set a group apart**, for groups of at least 3 sampled lines. Background:
  12 slices of the window over the query's stream selector without its pipeline, 200 lines
  each, the lines of the group's services whose level is not ERROR. Fields: stream labels
  and scalar JSON fields from `EventNormalizer.parse`, without the timestamp, message,
  level, logger, `error.*`, process id and values longer than 120 characters; digit runs in
  thread names become `*`. No label or field names from a stand in code.
  - **Contrast:** a value held by at least half of the group's lines, whose share among
    background lines that carry the field (at least 20 of them) is at least 0.4 lower.
  - **Concentration:** fewer than 20 background lines carry the field, every group line has
    the same value, and the field varies in the sampled and background lines of the group's
    services (changed in S19: over the whole sample the build of a PR stand "varied" because
    other services had other builds).
  - Values covering exactly the same lines are one finding; at most two findings a group,
    printed under it with the share of each value (changed in S19, see below):
    `all 6 lines: pod ssj-main-asv-…p-connect-76566db777-gbwck (7% in other lines of
    ssj-main), node_name … (15%), build.version 2799 (43%), …`.

### Work packages

- **S18 — history of groups and the "not now" block** (done 2026-09-24). What the work
  added to the plan:
  - The fragment is text of the stand's log lines, and `LokiHttpClient` logs the `query` of
    every request: the stdio smoke caught the fixture line in the server log. A history
    request is logged with the fragment replaced by `<log text>` (`queryRange` with a
    `loggedQuery`).
  - The container test pushes the same text a day before the window, 0.1 s after the same
    hours begin; both the 24-hour period and the same-hours evaluation have to catch it, so
    the offsets are checked to the second on Loki 2.6.1 and 3.6.0. A fresh Loki asks its
    ingester for recent data only (`query_ingesters_within`, 3 h), so the line is flushed
    to the store (`POST /flush`) and read back first; on 2.6.1 the flush empties `/series`
    of the window for a while, so this part runs last.
  - Both versions answer evaluations in the future (the step end at the next UTC midnight):
    the store logs "adjusting end timerange from future to now".
  - The fragments of all 13 groups of the fixture equal the ones counted on the stand, so
    the test stub answers exactly the requests DEV answered.
  - **The live run changed the requests.** Two requests per group, four at a time, got HTTP
    429 from the DEV Loki for every group, and the next `summarizeLogs` call failed at once
    with `UPSTREAM_RATE_LIMITED`: the query frontend splits a 7-day request by day and the
    tenant's queue was full of our sub-queries. One request for all groups replaced them:
    the selector narrowed to the groups' services, the query's pipeline, a literal
    alternation of the fragments (a substring search) and the same alternation as
    `| regexp "(?P<mcp_fragment>…)"`, summed `by (mcp_fragment, instance)`. For the 13
    groups of the fixture window it took 3.4 s (5.6 s without the narrowing) and returned
    the same counts as the 13 single requests; requests now go one after another, groups
    are packed up to 6000 bytes of URL-encoded query (a Cyrillic character is 6 bytes, an
    ingress passes 8 KB request lines), and no request starts after 20 s.
  - The same hours of a 24-hour window read 8 days of lines: 27 s on DEV, and the deadline
    left the second batch unasked. Windows over 6 hours are now compared with the daily
    median scaled to their length, and take their own count from the sample when it read
    the whole window, so a day costs the 7-day request only. `now-24h … now` carries
    nanoseconds and came out as 86 401 s; the length is the duration rounded up.
  - Measured on DEV after the change (Loki 2.6.1, `{namespace="dev", app=~"asv-app|sp-app"}
    |~ "ERROR|Exception|Caused by"`): 4 hours 8 s in all (11 groups, 2 count requests of
    2–3 s), 8 hours 11 s, 24 hours 34 s (34 groups in 2 batches, 13.5 s and 1.1 s; the
    sample itself 16 s). On a day window 13 of 34 groups were new and 3 more than usual.
  The plan as written: a `GroupHistory` (fragment,
  requests, classification) in `service`, a range-query path for it in `QueryService`, the
  header line and one line per group in `LogSummary`, `docs/queries.md` and the tool
  description. Tests from `asva2-dev-contrast-history.json` with a stub client: the four
  new groups (the `TASK_DB_FAILED` disagreement above kept as data), the task failure and
  the `selectedDateTime` noise "more than usual", the 7 groups "seen before", the offset and step of the generated request for a window
  ending at 13:06:56 MSK, the fragment of a JSON line with quotes, a group from a JSON-field
  service without a label matcher, the "not now" block from
  `asva2-dev-contrast-yesterday.jsonl` shown only when the window was read whole, the
  summary of the window under its budget with both blocks.
- **S19 — fields that set a group apart** (done 2026-09-24). What the work added to the
  plan:
  - One share for a merged finding misled: the pod of `DeleteDraftUploads` holds 7% of the
    other `ssj-main` lines, the lines holding all six values together 36% of those carrying
    all six fields. Every value now prints its own share, lowest first, at most four
    (`(+2 more)`); a long value is cut in the middle, because pod names differ at the end.
  - The background selector is the query's stream selector without its pipeline and level
    matchers, narrowed to the services of the groups of 3 lines or more; slices are read
    in the order 0, 7, 2, 9, … so that the shared 20 s deadline (history first, then the
    background, then the "gone" page) leaves an even sample. `filename` and `job` (promtail
    labels repeating the pod) are not fields.
  - On DEV over 24 hours the block found what no single group shows: `DataSource health
    check failed` in `audit-main` (54 lines), `ssj-main` (12) and `parus-main` (4), every
    line on `k8s-node1` against 1–4% of the other lines of those services — a node, not the
    services. Over 4 hours: the task failure on the pod of build 2799 (5%), the upload errors
    in the thread `Информация по загрузке` (1%) of one user. Cost: a 4-hour summary 10.5 s in
    all (8 s before S19), 24 hours 33 s.
  The plan as written: a `FieldContrast` in `service` (background
  slices, contrast and concentration, merging), one or two lines under a group. Tests from
  `asva2-dev-contrast-window.jsonl` and `-background.jsonl`: pod/build/commit of
  `DeleteDraftUploads` as one finding, thread and `userId` of `Загрузка файлов невозможна`,
  no finding from plain-text background lines, nothing for a group of fewer than 3 lines,
  the existing `LogSummaryTest` / `StdioSmokeTest` unchanged apart from the new lines.

## Open items

- Manual end-to-end run with DeepSeek 4.1 Flash ("the DEV stand is broken, find out why");
  fix tool descriptions, `instructions` and hints from its protocol; decide on MCP prompts.
- CI workflow (`.github/workflows/build.yml`) runs on push: green up to `95ff6e3`; the
  S14–S17 pushes failed in `integrationTest` only, because `LokiCompatibilityTest` still
  expected the fully qualified root type in the summary (fixed 2026-09-23; `integrationTest`
  green locally on 2026-09-24 with S18, not yet re-run on CI). Still to do: move to
  `actions/setup-java@v5` (v4 is deprecated) and consider `git update-index --chmod=+x
  gradlew` instead of the `chmod` step.
- Docker image: deferred; the stdio server is launched by a local MCP client.
- Grafana Explore links, Grafana proxy transport: only if a real need appears.
- asva2 side, not ours: the DEV promtail lacks the JSON stage (no `applicationName` /
  `level` labels on ECS streams as of 2026-09-21); the helm upgrade of `loki-stack` is
  pending there.
