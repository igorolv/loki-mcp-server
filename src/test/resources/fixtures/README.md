# Test fixtures

`asva2-dev-errors.jsonl` — 37 error lines of the asva2 Java services taken from the DEV
stand on 2026-09-21 (see `docs/decisions.md`, "Analysis inside the server"). One JSON
object per line: `ts` (epoch nanoseconds as a string), `labels` (the Loki stream labels
`app`, `container`, `instance`, `namespace`, `pod`, `node_name`) and `line` (the log line
as Loki returned it: Spring Boot ECS JSON, one plain-text line from `address`).

The selection is one shortest line per root-cause group of the day plus every line of one
correlated task failure (`taskExecutionId=500001`: two `ssj-backend` lines and one
`scheduler-backend` line) and the broken-pipe pair. Anonymised with stable substitutes,
so equal identifiers stay equal: UUIDs → `00000000-0000-4000-8000-<n>`, user ids →
`1001…`, `…Id=<n>` / `…ID: <n>` → `500001…`, git commits → `<n>` padded to 40 digits,
e-mails → `user@example.com`, hosts → `*.example.internal`. Timestamps, service names,
loggers, stack frames and Russian message texts are original.

`asva2-dev-lifecycle.jsonl` — 320 lines of the same stand from 2026-09-22 for the releases
`sbp-main`, `sms-main`, `parus-main`, `ssj-main` and `ssj-pr-1375`: every line the Loki side
of the start/stop query (`|~ "Start|Graceful shutdown complete"`) returns — Spring Boot
`Starting` / `Started`, `Graceful shutdown complete`, and the lines that only look alike
(`HikariPool-N - Start completed`, `Starting service [Tomcat]`, `KafkaStartupLogger`) — plus
the nine Flyway `Schema "…" has version …` errors logged during those starts. Same format
and labels as above. Git commits → `c0<n>` padded to 40 characters (distinct in the first
10), node hosts → `*.example.internal`; pod names, versions, build numbers and messages
are original.

The four `asva2-dev-contrast-*` files are one sample of the same stand for the baseline and
field comparison (`docs/decisions.md`, "Baseline and field contrast"), all for the query
`{namespace="dev", app=~"asv-app|sp-app"} |~ "ERROR|Exception|Caused by"`:

- `asva2-dev-contrast-window.jsonl` — every line of that query on 2026-09-24
  09:06:56–13:06:56 MSK (67 lines, 13 groups of `summarizeLogs`).
- `asva2-dev-contrast-yesterday.jsonl` — the two shortest lines of every group of the same
  hours on 2026-09-23 (25 lines of 807; that day had a `Connect timed out` burst of 570
  lines in nine services).
- `asva2-dev-contrast-background.jsonl` — lines of the window's services without the error
  filter, from 12 slices of the window, at most 150 per service (521 lines). Structure only:
  the JSON keeps the timestamp, level, logger, thread, service, version, `applicationName`,
  `userId`, build and commit, and `message` is `(message removed)`; a plain-text line is
  its level, if any, plus `(plain line removed)`. The messages held names of employees,
  e-mails, session ids and addresses.
- `asva2-dev-contrast-history.json` — per group of the window, what Loki counted with the
  query plus `|= "<fragment>"` over the group's `instance`: `window` (the 4 hours),
  `sameHours` (the same 4 hours on each of the 7 previous days, oldest first) and
  `previousDays` (the 7 periods of 24 hours before the window start, oldest first).

Same substitutes as above (stable within these four files): UUIDs, `userId` (`-1`, the
anonymous user, is kept), `…Id=<n>` / `ID = <n>`, git commits, e-mails, `*.it-spectrum.ru`
hosts, IP addresses → `192.0.2.<n>`. The `filename` and `job` labels are dropped. Pod
names, build versions, thread names and messages of error lines are original.
