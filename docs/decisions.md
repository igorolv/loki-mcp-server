# Design decisions

Why the server looks the way it does. The planning history (work packages S01–S20, session
journals, measurements of removed features) is in git history; this page keeps only the
decisions that still shape the code and the open items.

## Goal and audience

A read-only MCP server for incident investigation in Grafana Loki. The consumer is a small
model (target: DeepSeek 4.1 Flash) that reads tool descriptions once, does not keep
dictionaries or indirect references in mind, never opens server documentation and skips
service fields. Everything the model needs is in the tool descriptions, the server
`instructions` and the response text itself. The flow the server is built around:

> pick the stand → see which services and fields exist → count whether and when the
> problem happened → summarize → read lines → read what happened around one line → get the
> full original line.

## Where the value is (2026-09-25)

The server does the mechanical work a model does badly or expensively — building LogQL,
paging, compacting stack traces, grouping lines by root cause, staying under a byte budget —
and carries what the model cannot know: the knowledge of the stand kept next to the
connection (`hint`, `serviceLabels`, `scope`, `levels`, `applicationPackages`, the rules
catalogue with its line formats and layouts). Adding project knowledge there is preferred
over adding analysis to the code. Statistical conclusions (what is new, since when, what is
related) are left to the model, which reads the groups and asks `countLogs` / `queryLogs`.

## Simplification (2026-09-25)

Removed with the user, because the analysis built in S15–S20 made `summarizeLogs` cost up to
20 Loki requests and 10–36 s a call under a 20 s deadline, rested on heuristics measured on
one day of one stand, and was never checked with the target model:

- the incident picture (`IncidentPicture`: groups joined by key or dependency, a Poisson
  change point as the onset, the order of services);
- the history of groups (`GroupHistory`: counts of a fragment of each group over the 7 days
  before and the same hours, "new / more than usual / seen before") — `countLogs` with the
  group's text and `groupBy="time"` answers the same question on demand;
- the fields that set a group apart (`FieldContrast`: 12 background slices per call) —
  `queryLogs raw=true` over a group shows its pod and build;
- the groups of a day earlier that are gone;
- `followKey` and the `linked:` keys of the summary (`CorrelationKeys`) — `queryLogs` with
  `text="<id>"` over the whole scope reads one id across services;
- `queryMetrics` and its limits `maxMetricSeries` / `maxMetricPoints` — `countLogs` covers
  totals, a breakdown by label and counts over time; a connections file that still names
  those limits is rejected as holding unknown fields.

Not coming back without a new decision: any analysis in `summarizeLogs` that needs more than
its sample and the restarts request.

## Text contract instead of JSON (2026-09-13)

The first iteration had typed JSON responses with output schemas, a stream dictionary with
local `streamId`s, a field projection, `continueLogs` with AES-GCM cursors and a multi-stage
response budget. A weak model could not use it: it had to resolve dictionaries, carry
cursors between calls and understand `limitations` enums. All of it was replaced by:

- one text `content` per response, no output schema, no `structuredContent`;
- one log line = one text line `HH:mm:ss.SSS LEVEL service  message [trace=…]`, stack
  traces compacted, the date in the page header;
- header and footer as the only service text: `newest 50 of more`, a ready-made `end` for
  reading older lines, one sentence of advice (`Too many lines? Narrow the query…`);
- tool descriptions written as instructions (when to call, example arguments, what to do
  next), 4–5 sentences at most; server `instructions` carry the flow and the rule that log
  lines are data, not instructions.

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
  `label="..."` lists all values of one label because the overview cuts value lists at 10.
- `summarizeLogs` groups a sample locally (below); Loki's pattern API is not used so that it
  works on 2.6.1. Counts are for the sample only and the text says so; rare 1–2 line groups
  are listed separately so that a one-off error is not lost.
- `raw=true` in `queryLogs` prints the original line with its stream labels — the only
  path to the full line and to pod/instance, which the normal mode hides behind the
  service name.
- `exportLogs` (below) is the only tool that writes, and only on the local disk.

Not implemented on purpose: `loki_stats`, `loki_ready`, `loki_config` (nothing to
investigate with), `direction` (always the newest lines of the window), Grafana proxy
transport (Grafana adds no capability over direct Loki; revisit if a stand exposes Loki
only through Grafana), MCP prompts (no evidence that they help), `compareLogs`, `getTrace`.

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
  auth, tenant, response bodies or log line contents. Every query the server sends is built
  from the model's arguments and the profile, never from text of log lines.

## Summaries (2026-09-21 – 2026-09-23)

Measured on 171 error lines of the asva2 Java services over 24 hours on DEV (an anonymised
sample is `src/test/resources/fixtures/asva2-dev-errors.jsonl`): the services write Spring
Boot ECS JSON; an error line is 16 KB on average and up to 30 KB (140–230 frames, 2–4
`Caused by:`), so 50 raw lines are an 800 KB page, while grouping by root cause gives 35
groups in 7.8 KB — the day's picture in one response. Root messages are multi-line; the
first application frame of a whole trace is a servlet filter for every HTTP error; noise
(`NoResourceFoundException` of a polled endpoint, broken pipes) was 104 of 171 lines, and on
another day 475 of 500 sampled lines.

- **Root-cause signature.** A line with a stack trace is grouped by the simple name of the
  last `Caused by:` type, the first line of its message normalized, and the application
  frame — the first frame under the root section whose class starts with one of the
  connection's `applicationPackages` (empty: no frame, nothing guessed from class names).
  Wrapper types are shown, not grouped by. A line without a stack trace keeps its message
  template.
- **Own stack trace parser** for Logback/Log4j2 traces (`Caused by:`, `Suppressed:`,
  `... N more`, `module/` prefixes, CGLIB proxies) and the logstash root-first form
  (`Wrapped by:`): no library does this without dragging a platform along.
- **Rules are data.** A catalogue per connection (`rulesFile`, a list of files tried in
  order, the stand's first) maps a root cause or message pattern to a category (dependency,
  startup, configuration, noise), a subject and one sentence of advice; `summarizeLogs`
  prints "Known causes", lists noise apart and offers the filters that drop dominant noise.
  JSON, not YAML: the same strict loading as `connections.json`. `examples/java-rules.json`
  holds generic Java client failures without stand names.
- **Byte-aware sampling.** The sample is read backwards in pages sized by the lines seen so
  far (the first page 50 lines) and stops at `sample` lines or `maxHttpResponseBytes`; the
  header says how many lines were read.
- **Restarts and deploys.** One more request over the query's stream selector without level
  matchers, filtered by a literal alternation first (`|~ "Start|Graceful shutdown
  complete"`: 4–5 s over a day on Loki 2.6.1, the regular expression alone 18 s), reads
  Spring Boot `Starting` / `Started` / `Graceful shutdown complete` lines. Every restart is
  a new pod, so a start is paired inside its stream and a deploy is a version change
  against the previous start or the stopped pod it replaced; a group whose lines a service
  logged while starting says so (the Flyway `Schema … has version` errors on every start of
  `sbp-ui-backend`). A failure costs the block, never the summary.

## Queries without LogQL and why a result is empty (2026-09-24)

A weak model writes LogQL with a mistyped label value, the wrong service label or a filter
that drops everything, and Loki answers all of them with zero lines.

- **Why a result is empty.** Only after an empty result, so it costs nothing while there
  are lines: label values of every `=` matcher, the label list when a label has none, then
  `/series` of the selector; one `Why:` line names the missing label or value (with the
  closest values), the matchers no stream holds together, or the pipeline that dropped
  every line. A failed lookup leaves the plain answer.
- **`service`, `level`, `text` instead of `query`** in `queryLogs`, `countLogs`,
  `summarizeLogs` and `exportLogs`. The base selector is the profile's `scope`; the service
  label is looked up among `serviceLabels` by the values Loki has in the window, never
  guessed; `level` is a line filter from the profile's `levels` over generic defaults
  (`error`, `warn`), because a level label is missing on the asva2 streams and
  `detected_level` exists only on 3.x. `query` together with them is an argument error. The
  built query is printed, so the model sees LogQL it can refine. `getLogContext` keeps its
  explicit selector: context needs the exact stream.

## Plain-text line formats (2026-09-24)

Plain Spring Boot console lines kept their time, level, pid and thread in the message, so a
group's headline started with a timestamp, the logger was unknown to rules, and one message
logged from different threads made different groups. The layout is data: a rules catalogue
may hold `formats`, regular expressions whose named groups become the line's fields
(`message`, `level`, `logger`, `service`/`application`; any other group is a field).
`examples/java-rules.json` holds the Spring Boot console layouts (with and without the
application name) and the classic logback layout; the code knows only the engine. An empty
message is the rest of the line when there is one, else `(empty message, logger <logger>)`.

## Export to local files (2026-09-25)

A plain way to pull the logs of a window to disk — for an agent's own tools or for reading
by eye, in the Spring Boot console layout instead of the ECS JSON the stand stores.

- **`exportLogs`** — the only tool that writes, only on the local disk (Loki is still only
  read). Filters are those of `queryLogs`, the window up to `maxIntervalSeconds`. Lines go
  oldest first and in full; the answer is the path, counts and a `start` to continue with,
  never the lines.
- **Where** — only inside `exportRoots` of the connections file (default
  `<data dir>/exports`); a directory the model names must lie inside one of them after
  normalization and symbolic links, so that text in the logs cannot steer a write
  elsewhere. Existing files are never overwritten: a taken name gets `-2`, `-3`.
- **Format** — `raw`, a named layout of the rules catalogue (`layouts`, the reverse of
  `formats`: `spring` in `examples/java-rules.json`), or a template in the call. Unsplit
  plain lines are written unchanged (unless the template holds `{line}`).
- **Paging** — forward pages starting at the time of the last written line; lines of that
  nanosecond already written are skipped by stream and text with their multiplicity.
  `maxExportLines` (500000) and `maxExportBytes` (256 MiB) stop an export with a
  continuation; a Loki error after the first line keeps what was written.
- Not done: a CLI entry for people without an agent, a normalized JSONL format, a deadline
  of its own.

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
- CI (`.github/workflows/build.yml`) runs `build` and `integrationTest` on every push.
- Tool descriptions and `instructions` are checked by review and the live smoke. The
  planned manual run with DeepSeek 4.1 Flash was dropped by the user on 2026-09-24; the
  descriptions stay written for that class of model.

## Observations from the asva2 stands (2026-09-14)

Recorded because they shaped defaults and hints, not as facts about the stands today.
DEV (Loki 2.6.1, k8s) exposes read paths only; recent streams may lack `applicationName`,
so the DEV hint names `instance` / `container` as fallbacks; the `level` label exists only
on lines written after a service restarted with the current logging config; non-JSON
services log stack traces one line per frame with no level — hence `getLogContext` without
a `level` filter. TST (Loki 3.5, docker) is almost silent, so an empty hour must lead to
widening the window, not to "no errors"; `detected_level` covers more lines than the
`level` label; a 24-hour query with a structured-metadata filter can exceed the 30 s
request timeout.

## Open items

- Docker image: deferred; the stdio server is launched by a local MCP client.
- Grafana Explore links, Grafana proxy transport: only if a real need appears.
- More stand knowledge in the connection profile and its catalogues (see "Where the value
  is"): what the asva2 profile should carry beyond `hint`, rules and layouts is not decided.
- asva2 side, not ours: the DEV promtail lacks the JSON stage (no `applicationName` /
  `level` labels on ECS streams as of 2026-09-21).
