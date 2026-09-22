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
