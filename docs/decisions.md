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
- **S17 — restarts and deploys.** `Started *Application`, `HikariPool-* - Start
  completed`, a change of `service.version` / `build.version` / `git.commit` inside a
  stream as timeline events; later the one-call incident overview built from S14–S17.

## Open items

- Manual end-to-end run with DeepSeek 4.1 Flash ("the DEV stand is broken, find out why");
  fix tool descriptions, `instructions` and hints from its protocol; decide on MCP prompts.
- CI workflow (`.github/workflows/build.yml`) has not run yet — check after the first push;
  consider `git update-index --chmod=+x gradlew` instead of the `chmod` step.
- Docker image: deferred; the stdio server is launched by a local MCP client.
- Grafana Explore links, Grafana proxy transport: only if a real need appears.
- asva2 side, not ours: the DEV promtail lacks the JSON stage (no `applicationName` /
  `level` labels on ECS streams as of 2026-09-21); the helm upgrade of `loki-stack` is
  pending there.
