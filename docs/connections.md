# Connections and listConnections

The server loads the configuration once at start-up. `listConnections` returns text — one
line per connection: name, description and the operator's hint — and never contacts Loki.
Reading goes through [queryLogs, countLogs, summarizeLogs, getLogContext,
exportLogs](queries.md) and [discoverLogs](discovery.md). A connection being listed does
not confirm that its endpoint is reachable.

The default file is `~/.loki-mcp-server/connections.json`. `LOKI_MCP_DATA_DIR` changes the
directory for data, logs and the default file. `LOKI_MCP_CONNECTIONS_FILE` sets a separate
file path; a relative path is resolved against the process working directory. Equivalent
Spring properties: `loki-mcp.data-dir` and `loki-mcp.connections-file`.

Minimal configuration:

```json
{
  "connections": {
    "local": {
      "description": "Local Loki",
      "url": "http://localhost:3100"
    }
  }
}
```

A full example is [examples/connections.json](../examples/connections.json). Set the
environment variables listed there before use, or remove the `secured` connection. Never
commit real configurations or credentials. Names and descriptions are public: do not put
secrets into them.

Format: an object `connections` with one or more connections. Names are case-sensitive,
1–64 characters, ASCII letters, digits, dot, dash and underscore; the first character is a
letter or a digit. `description` is optional, up to 512 characters. `hint` is an optional
hint for the model, up to 1024 characters: which labels the stand has, how to pick a
service. `serviceLabels` is an optional list of labels that name the service on a line
(default `service_name, service, app, container, job`).
`applicationPackages` is an optional list of up to 32 Java package prefixes of the stand's
own code (`["ru.it_spectrum.asv", "ru.it_spectrum.core"]`): `summarizeLogs` shows the
nearest frame of these packages under the root cause of a stack trace. Without it no frame
is recognised as own code and nothing is guessed from class names. `ignoredFrames` is an
optional list of up to 32 regular expressions matched against `Class.method` of such a
frame: a frame they match is never the one shown — a request filter of the stand's own code
that every call passes (`["\\.doFilter(Internal)?$"]`). `versionFields` is an optional
ordered map of up to 8 JSON fields that tell a build to the word printed before the value
(`{"service.version": "", "build.version": "build", "git.commit": "commit"}` prints `main
build 2792 commit c000000008`; a value over 16 characters, a commit hash, is cut to 10); the
default is the ECS `{"service.version": ""}`. The restarts block of `summarizeLogs` reads
the version from these fields. `rulesFile` is an
optional path to a rules catalogue (below) or a non-empty list of them, tried in order —
the stand's own file first, then generic sets (`["asva2-rules.json", "java-rules.json"]`);
a relative path is resolved against the directory of the connections file, `${VARIABLES}`
are substituted, and connections naming the same file share one loaded copy. `scope` is an
optional stream selector of the stand's services (`{namespace=~"dev|asv-dev"}`, up to 1000
characters, a selector only): queries built from `service`, `level` and `text`
([queries.md](queries.md#queries-without-logql)) start from it. `levels` is an optional map
of up to 16 level names (lower-case letters) to the LogQL line filter that selects them
(`{"error": "|~ \"ERROR|Exception|Caused by\""}`, starting with `|`, up to 300 characters);
it overrides the defaults `error` → `|~ "ERROR|FATAL"` and `warn` → `|~ "WARN"`. A Java
stand whose shipper sends stack trace lines apart adds `Exception|Caused by` to its own
`error`, as the asva2 profile does. `listConnections` names the levels and the scope of every connection. The registry never picks a default connection, even
with a single entry, and never trims names.

### Export directories

`exportRoots` at the top level of the file (next to `connections`) lists up to 16
directories `exportLogs` may write into; the first one is the default. A relative path is
resolved against the directory of the connections file, `${VARIABLES}` are substituted; an
empty list or a blank entry stops the start-up. Without `exportRoots` the only directory is
`exports` in the data directory (`~/.loki-mcp-server/exports`). Directories are created on
the first export. A directory the model passes must lie inside one of them after
normalization and after symbolic links are resolved; the error message lists them.

```json
{
  "exportRoots": ["C:/logs/loki", "exports"],
  "connections": { "dev": { "url": "http://localhost:3100" } }
}
```

### Rules catalogue

What known kinds of lines of a stand mean, so that `summarizeLogs` can say it instead of
the model working it out: a JSON object with a list `rules`, tried in order, the first
match wins. The asva2 starting set is
[examples/asva2-rules.json](../examples/asva2-rules.json);
[examples/java-rules.json](../examples/java-rules.json) is a generic set of Java client
failures (JDBC connections, Redis, Kafka, HTTP 5xx, connect and read timeouts, Flyway, a
failed Spring context, out of memory) without stand names, meant to go after a stand's own
file.

```json
{
  "rules": [
    {
      "id": "flyway-schema-ahead",
      "category": "startup",
      "match": { "message": "Schema \"(?<schema>[^\"]+)\" has version (?<version>[\\d.]+), but no migration could be resolved" },
      "subject": "schema ${schema}",
      "advice": "The service stops at start-up: schema ${schema} is at version ${version}, newer than the migrations of this build."
    },
    {
      "id": "missing-endpoint",
      "category": "noise",
      "match": { "exception": "^NoResourceFoundException$" },
      "advice": "A client calls an endpoint that does not exist; not a failure of this service.",
      "filter": "!= \"NoResourceFoundException\""
    }
  ]
}
```

- `id` — 1–64 characters: lower-case letters, digits and dashes; unique in the file.
- `category` — `dependency` (another system failed: a database, a queue, a neighbouring
  service, an agency), `startup` (the service did not start), `configuration` (its own
  settings are wrong or missing) or `noise` (known harmless lines).
- `match` — Java regular expressions, searched (not anchored); every given one must match
  and at least one is required. `exception` is tried against the simple class names of the
  root cause and of every wrapper (so it needs a stack trace), `message` against the logged
  message (its first 2000 characters), then the root cause message, then the wrapper
  messages, `logger` against the logger name (`log.logger`, `logger_name`). Up to 1000
  characters each.
- `subject` — what failed, up to 300 characters; required for `dependency`. Lines are summed
  by category and subject in the "Known causes" block.
- `advice` — one sentence for the model, up to 300 characters, printed under the group.
- `subject` and `advice` may use `${name}` of named groups of `message`; a name the pattern
  does not define is a configuration error.
- `filter` — for noise: LogQL line filters that drop these lines (`!= "..."`, `!~ "..."`,
  several allowed), offered when noise crowds a sample.

### Line formats

A catalogue may also hold `formats`: how plain-text (not JSON) lines of the stand are laid
out, so that their level, logger, thread and message are read like JSON fields.

```json
{
  "formats": [
    {
      "id": "spring-boot-console",
      "pattern": "^(?<time>\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?)\\s+(?<level>TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\\s+(?<pid>\\d+)\\s+---\\s+(?:\\[(?<application>[^\\]]*)\\]\\s+)?\\[\\s*(?<thread>[^\\]]*?)\\s*\\]\\s+(?<logger>\\S+)\\s*:\\s?(?<message>.*)$"
    }
  ],
  "rules": []
}
```

- `id` — like a rule id, unique across the files of a connection.
- `pattern` — a Java regular expression searched in the first line of the line; the named
  group `message` is required. `level`, `logger`, `service` / `application` and `message`
  are read like the JSON fields of those names; other groups are fields of the line; `time`
  and `pid` are never compared.

Formats of all files are tried in order, the first match wins; up to 32 per connection.
`examples/java-rules.json` holds the Spring Boot console layout (Boot 2, 3 and 3.4+ with the
application name before the thread) and the classic logback one
(`2026-09-24 23:08:46 [scheduling-1] ERROR a.b.TaskService - message`).

### Line layouts

`layouts` are the reverse of `formats`: named templates `exportLogs` writes lines in
(`format="spring"`). `listConnections` names them.

```json
{
  "layouts": [
    {
      "id": "spring",
      "template": "{time} {level:5} {process.pid|pid} --- [{service}] [{process.thread.name|thread_name|thread}] {logger} : {message}{stack}"
    }
  ],
  "rules": []
}
```

- `id` — lower-case letters, digits and dashes, not `raw`. When two files of a connection
  name the same id, the earlier file (the stand's own) wins.
- `template` — up to 500 characters on one line. `{time}` (`yyyy-MM-dd'T'HH:mm:ss.SSSXXX`
  in the connection's timezone, or `{time:<pattern>}`), `{level}`, `{service}`,
  `{logger}`, `{message}`, `{traceId}` and `{line}` (the original line) are the normalized
  fields; `{stack}` is a newline and the full stack trace, or nothing. Any other name is a
  field of the line (a dotted JSON path, `process.thread.name`, or a group of a line
  format, `thread`), then a stream label, then structured metadata. `{a|b}` takes the first
  name that has a value; `{level:5}` pads on the left, `{logger:-40}` on the right; `{{`
  and `}}` are literal braces; a missing value is empty. An invalid template stops the
  start-up; in a call it is an argument error.
- A plain-text line that no format of the connection splits (an nginx access line, a stack
  frame logged as its own line) is written unchanged: a layout rewrites the fields it knows,
  never text it cannot read. A template holding `{line}` (`{time} {service} {line}`) is
  applied to every line. An empty message stays empty.

Up to 32 layouts per connection. The same template syntax is accepted as `format` in an
`exportLogs` call.

### Systems

`systems` are the names people use for parts of the project, each standing for the services
it is made of, so that `service="ССЖ"` works in `queryLogs`, `countLogs`, `summarizeLogs`
and `exportLogs`. They belong to the project, not to one stand, so they live in the
project's catalogue (`examples/asva2-rules.json`) that its connections share.

```json
{
  "systems": [
    { "names": ["ССЖ", "ssj"], "about": "deposit insurance, the main business system",
      "services": ["ssj-backend", "ssj-ui-backend", "ssj-ws-backend", "ssj-main"] }
  ],
  "rules": []
}
```

- `names` — 1–8 names, matched without regard to case, unique across the systems of a
  connection; the first is the one printed. `about` — optional, up to 200 characters.
- `services` — 1–32 values of the connection's `serviceLabels`. List every form a stand
  uses: the Spring names of one stand and the helm release of another (`ssj-backend` and
  `ssj-main`). A query takes the first service label holding at least one of them (and
  every other name of the call) and keeps only the values found in the window, so a service
  that logged nothing is left out; none found is an argument error.

`listConnections` ends the line of a connection with `service also takes a system name: ССЖ
(ssj): deposit insurance, the main business system; …`. The built query in the answer shows
what a name stood for. Up to 64 systems per connection.

Up to 200 rules per connection, ids unique across its files; unknown fields, an invalid pattern or category, a missing `advice` or a
file above 1 MB stop the start-up with the same message as a bad connections file.
Rules name causes outside the code; bugs of the application are left to the root-cause
grouping.

`url` is required: an absolute HTTP/HTTPS URL, a path prefix is allowed. User info, query
and fragment are forbidden. Authorization is a separate `auth`:

- No `auth`, or `{"type":"NONE"}` — no credentials.
- `{"type":"BASIC","username":"reader","password":"${LOKI_PASSWORD}"}`.
- `{"type":"BEARER","token":"${LOKI_TOKEN}"}`.

Mixing modes and control characters in credentials are rejected. `tenant` is optional and
goes to `X-Scope-OrgID`; an empty value and control characters are rejected. `${VARIABLE}`
is supported in URL, username, password, token and tenant. Substitution happens once,
without shell evaluation or default values; a missing variable or a malformed placeholder
is an error. `timezone` defaults to `UTC` and is validated through Java `ZoneId`.

The `limits` object is optional; every omitted field takes its default:

| Field | Default | Allowed |
|---|---:|---|
| `connectTimeoutMs` | 5000 | positive int |
| `requestTimeoutMs` | 30000 | positive int |
| `maxHttpResponseBytes` | 8388608 | positive int |
| `maxResponseBytes` | 65536 | int, at least 1024 |
| `maxEntries` | 1000 | positive int |
| `maxIntervalSeconds` | 86400 | positive long |
| `maxExportLines` | 500000 | positive int |
| `maxExportBytes` | 268435456 | positive long |

The HTTP client applies auth/tenant, `connectTimeoutMs`, `requestTimeoutMs` and
`maxHttpResponseBytes` (see [http-client.md](http-client.md)); the services apply
`maxEntries` (also the cap of `sample` and the page of `exportLogs`), `maxIntervalSeconds`
and `maxExportLines` / `maxExportBytes` (where one
`exportLogs` call stops and offers a continuation). `maxResponseBytes` is the response text limit:
[contract](queries.md#size-limit). `listConnections` returns the full list within 65536
bytes or an error. The file itself is limited to 1 MiB. Unknown fields are rejected.

A missing, empty or invalid file, duplicate JSON keys, unknown fields, fractional or string
numeric limits stop the start-up. The original JSON, credentials, URLs and the parser's
exceptions are not included in diagnostics; the message suggests checking the
configuration against the example. Changes require a restart.

`listConnections` response:

```
dev — asva2 DEV stand, Loki 2.6.1. Labels: applicationName, level, instance, pod
tst — TST stand, Loki 3.5
```

Description and hint are omitted when not set. URLs, credentials and limits are never
printed.

Errors are the text `Error <CODE>: <message>` with `isError=true` (internal `ToolError`:
`code`, `message`, `retryable`). Base codes: `CONFIGURATION_ERROR`, `CONNECTION_REQUIRED`,
`INVALID_CONNECTION`, `UNKNOWN_CONNECTION`, `INTERNAL_ERROR`. Transport codes are in
[http-client.md](http-client.md). `LokiOperationException` carries a safe `ToolError`
between layers; `Errors.from` turns an unexpected exception into a fixed internal error
without the original text. A configuration failure ends the process before any tool is
served; `listConnections` has no connection argument.
