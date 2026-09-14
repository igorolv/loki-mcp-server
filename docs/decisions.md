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

## Open items

- Manual end-to-end run with DeepSeek 4.1 Flash ("the DEV stand is broken, find out why");
  fix tool descriptions, `instructions` and hints from its protocol; decide on MCP prompts.
- CI workflow (`.github/workflows/build.yml`) has not run yet — check after the first push;
  consider `git update-index --chmod=+x gradlew` instead of the `chmod` step.
- Docker image: deferred; the stdio server is launched by a local MCP client.
- Grafana Explore links, Grafana proxy transport: only if a real need appears.
