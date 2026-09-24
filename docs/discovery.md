# Discovery and line normalization

## discoverLogs(connection, selector, start, end, label)

A text overview of what the logs contain and a ready-made selector for the next call.
`start`/`end` default to the last hour. With `label` only the values of one label are
returned (see below).

Without `selector`: label names from `/labels` (up to 30, alphabetically), the values of
each from `/label/<name>/values`. The line sample is taken by the first label of the
connection's `serviceLabels` that exists on the stand (`{applicationName=~".+"}`),
otherwise by the first label.

With `selector` (a stream selector in braces only, values in double quotes, no filters or
pipelines): labels and values from `/series` (the first 2000 streams), the sample is a
backward `query_range` by that selector, up to 20 lines.

```
Streams matching {namespace="dev"} in 2026-09-13 10:00:00–11:00:00 (+03:00): 84.
Labels:
  applicationName: nsi-backend, ssj-backend, ssj-ui-backend (+2 more)
  level: debug, error, info, warn
  pod: 84 distinct values (high cardinality, not listed)
Line format (20 newest lines sampled): JSON 19, plain text 1.
Levels seen: ERROR, INFO, WARN.
JSON fields (after | json): _timestamp, log_level, message, service_name, error_stack_trace, traceId (+3 more).
Example line: {"@timestamp":"2026-09-13T07:12:03.123Z","log":{"level":"ERROR"},...
Next: use countLogs or queryLogs with a selector like {namespace="dev", applicationName="nsi-backend"}; filter JSON fields with | json, e.g. | json | log_level=~"(?i)error"; filter text with |= "substring".
```

Rules:

- Up to 10 values per label, the rest as `(+N more: discoverLogs with label="<name>")`;
  more than 50 distinct values — "high cardinality, not listed". Values are cut at 60
  characters.
- JSON field names are printed the way `| json` exposes them in Loki: nested keys joined
  with `_`, other characters replaced by `_` (`log.level` → `log_level`,
  `@timestamp` → `_timestamp`). Ordered by the number of lines the field occurred in, up to
  30 names.
- `Levels seen` — level values from labels, structured metadata or the line, upper-cased;
  `detected_level="unknown"` of Loki 3.x counts as the absence of a level.
- The example is the newest sampled line, up to 300 characters; under the budget it is
  shortened to 75, then removed.
- The `Next:` hint adds the first value of the first `serviceLabels` label found, unless
  the selector already constrains it; the level filter is suggested by the field found
  (`log_level`, `level`, `severity`, `lvl`) or by the `level` label.
- The absence of a label or field in the sample does not prove its absence in the
  interval; the tool description says so to the model.
- An unavailable `/label/<name>/values` yields `(values not available)`; a failed line
  sample yields `No lines sampled in this window; fields are unknown.`; a failed `/series`
  or `/labels` is returned as a tool error.

## discoverLogs(label=...)

The overview shows at most 10 values per label; to get all of them (all services of a
stand, say) the model passes `label="applicationName"`. The response is the values from
`/label/<name>/values` (with `query=<selector>` when a selector is given), alphabetically,
one per line, up to 200; the line sample and the `Next:` hint are skipped:

```
Values of applicationName in streams matching {namespace="dev"}, 2026-09-13 10:00:00–11:00:00 (+03:00) (dev): 37.
auth-service
nsi-backend
...
(+12 more; narrow with selector)
```

Values are cut at 200 characters; under the budget the list is shortened from the end and
the `(+N more; narrow with selector)` counter stays honest. An empty response suggests
checking the label name in the overview without `label` or widening the window. An invalid
label name is an argument error.

## Line normalization

`EventNormalizer` produces `View(format, level, service, message, traceId, stackTrace,
jsonFields)` for `queryLogs`, `summarizeLogs` and discovery. Only the line is parsed;
labels and metadata are never overridden by it.

| Field | Sources in priority order |
|---|---|
| level | labels `level`, `detected_level`, `severity`, `lvl` → the same keys in structured metadata → JSON `log.level`, `level`, `severity`, `lvl`, `@l` → for plain text the first `TRACE/DEBUG/INFO/WARN/WARNING/ERROR/FATAL` word within the first 120 characters |
| service | the connection's `serviceLabels` in order (default `applicationName, service_name, service, app, container, job`) → JSON `service.name`, `service`, `app`, `application`, `applicationName` |
| message | JSON `message`, `msg`, `@message`, `event`, `@m`, otherwise the whole line; for plain text with frames — the first line |
| traceId | metadata → JSON → labels: `traceId`, `trace.id`, `trace_id`, `traceID`, `trace` |
| stackTrace | JSON `error.stack_trace`, `stack_trace`, `stacktrace`, `stackTrace`, `exception`, `throwable`; for plain text — the rest after the first line when it contains `\n\tat ` |

JSON is recognized only for lines that start with `{` and are an object; nested objects are
expanded to depth 20, at most 100 fields, lines no longer than 256 KiB. Dotted keys
(`"service.name"`) and nested objects (`"service":{"name"}`) give the same dotted path.
Arrays are not expanded. Everything else is plain text. The level is upper-cased. Nothing
is guessed.

A plain-text line is split by the first `formats` entry of the connection's rules catalogue
([connections.md](connections.md#line-formats)) whose regular expression finds a match in
its first line (up to 256 KiB). The named groups become the line's values, as if they were
JSON fields of those names: `message` replaces the first line as the message (the rest of a
multi-line line stays; a stack trace stays the stack trace; an empty one is the rest of the
line, or `(empty message, logger <logger>)` when nothing follows, as when a shipper sends
every line of a report apart), `level`, `logger` and `service`
(`application` too) fill those fields, and any other group (`thread`, `pid`, …) is a field of
the line for the field contrast of `summarizeLogs`. The code knows no layout; the Spring Boot
console layout is a format of `examples/java-rules.json`. Without a matching format the line
is handled as above. `jsonFields` stays empty for such a line.

## Verification

`gradlew.bat build --console=plain`: `EventNormalizerTest` (ECS, flat keys, label
priority, plain text, limits), `DiscoveryServiceTest` (with and without selector, value
caps, endpoint errors, budget, `label` mode), the stdio smoke.
`gradlew.bat integrationTest --console=plain`: discovery on Loki 2.6.1/3.6.0 (3.x adds
`service_name` itself — the tests do not assume an exact label set).
