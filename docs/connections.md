# Connections and listConnections

The server loads the configuration once at start-up. `listConnections` returns text — one
line per connection: name, description and the operator's hint — and never contacts Loki.
Reading goes through [queryLogs, countLogs, summarizeLogs, getLogContext,
queryMetrics](queries.md) and [discoverLogs](discovery.md). A connection being listed does
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
(default `applicationName, service_name, service, app, container, job`). The registry never
picks a default connection, even with a single entry, and never trims names.

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
| `maxMetricSeries` | 100 | positive int |
| `maxMetricPoints` | 10000 | positive int |

The HTTP client applies auth/tenant, `connectTimeoutMs`, `requestTimeoutMs` and
`maxHttpResponseBytes` (see [http-client.md](http-client.md)); the services apply
`maxEntries` (also the cap of `sample`), `maxIntervalSeconds`, `maxMetricSeries` and
`maxMetricPoints`. `maxResponseBytes` is the response text limit:
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
