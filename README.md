# Loki MCP Server

A local MCP server (stdio) that lets an agent read logs from Grafana Loki: overview of the
stand → count → summary → lines → context around an event → the full original line, and
saving the lines of a window to disk. Seven tools, every response is readable text; built for small models (target: DeepSeek 4.1 Flash). Loki is only read: push, delete, `/config` and other
management endpoints are never exposed; the only thing written is an `exportLogs` file inside the configured export
directories.

| Tool | What it does |
|---|---|
| `listConnections` | The stands from the configuration with the operator's hints |
| `discoverLogs` | Labels and values, line format, JSON fields, a ready-made selector; `label="..."` lists every value of one label |
| `countLogs` | How many lines match; `groupBy` by a label or `"time"` with spike markers |
| `queryLogs` | Lines in chronological order: `time LEVEL service message`, stack traces compacted; `raw=true` prints the line as is with its labels |
| `summarizeLogs` | Groups in a sample of the newest lines — errors by root cause and the application frame, other lines by message template: count, first/last time, example; rare ones listed separately; known causes and noise by the connection's rules; service restarts and deploys (Spring Boot start/stop lines with their version) in the same window, and which errors were logged while a service was starting |
| `getLogContext` | N lines before and after a moment in a stream, the target lines marked with `>>>` |
| `exportLogs` | Every matching line of a window saved to a file on the local disk, oldest first and in full: the original lines, a layout such as `spring` (the Spring Boot console line from ECS JSON), or a template of one's own; one file or one per service |

Loki 2.6.1 and 3.x are supported. The response contract is in
[docs/queries.md](docs/queries.md) and [docs/discovery.md](docs/discovery.md);
connections in [docs/connections.md](docs/connections.md).

## Build

JDK 21+ is required (a Java 21 toolchain is used).

```powershell
.\gradlew.bat bootJar
```

The jar is `build/libs/loki-mcp-server.jar`. On Linux/macOS use `./gradlew bootJar`.

## Connection configuration

The server reads `~/.loki-mcp-server/connections.json` (or the file named by
`LOKI_MCP_CONNECTIONS_FILE`). An example with hints for the asva2 stands is
[examples/connections.json](examples/connections.json); the stand URLs there come from the
environment variables `LOKI_DEV_URL` and `LOKI_TST_URL`. The minimum:

```json
{
  "connections": {
    "dev": {
      "description": "DEV stand, Loki 2.6.1",
      "hint": "Service = label applicationName. Whole environment: {namespace=\"dev\"}.",
      "url": "http://loki.example.internal",
      "timezone": "Europe/Moscow",
      "serviceLabels": ["applicationName", "container"]
    }
  }
}
```

`hint` is a short map of the stand for the model (which labels exist, how to pick a
service, known traps); `serviceLabels` says which labels name the service on a line;
`applicationPackages` names the packages of the stand's own code (the frame shown under a
root cause); `scope` and `levels` let `queryLogs`, `countLogs` and `summarizeLogs` take
`service`, `level` and `text` instead of LogQL; `rulesFile` points to a catalogue of what known lines mean — dependencies,
start-up failures, configuration errors, noise — which `summarizeLogs` applies; it takes
one file or a list, the stand's own first (the asva2 set is
[examples/asva2-rules.json](examples/asva2-rules.json), a generic Java set
[examples/java-rules.json](examples/java-rules.json)).
`exportRoots` at the top level lists the directories `exportLogs` may write into (default
`~/.loki-mcp-server/exports`), and a catalogue's `layouts` name the line templates it
writes (`spring` in the generic Java set).
Credentials go through `auth` (`BASIC` or `BEARER`) and `${VARIABLES}`, `tenant` becomes
`X-Scope-OrgID`; none of it is ever printed in responses or logs. The full format, defaults
and loading errors are in [docs/connections.md](docs/connections.md).

### Choosing a connection

Every tool except `listConnections` requires an explicit `connection`; no default stand is
substituted, even with a single entry. The model picks the name from `listConnections`, so
`description` and `hint` should answer "which stand is this and how do I find a service
here": the name of the service label, the selector of the whole environment, what to do
when the `level` label is missing. Keep similar stands of one system (DEV/TST/PROD) in one
file with telling names; keep different systems in separate files via
`LOKI_MCP_CONNECTIONS_FILE` and separate MCP client entries.

### Limits and time

The connection's `limits` object (all fields optional): `maxEntries` (1000) — the cap of
`limit` and `sample`; `maxIntervalSeconds` (86400) — the longest `start`–`end` window;
`maxResponseBytes` (65536) — the limit of one response text; when exceeded, `queryLogs`
drops the oldest lines and `summarizeLogs` drops rare groups, noise and restarted services
before the top groups, and the output says so;
`requestTimeoutMs` (30000) and `maxHttpResponseBytes` (8 MiB) — the limit of one request
to Loki; `maxExportLines` (500000) and `maxExportBytes` (256 MiB) — where one `exportLogs`
call stops and gives a `start` to continue with. Time: `now`, `now-15m` (`ns/ms/s/m/h/d`), RFC3339 with an offset
(`2026-09-13T10:00:00+03:00`), local time in the connection's `timezone`, or epoch
nanoseconds; `getLogContext` additionally accepts a time of day from a page
(`10:12:03.123`). Responses print time in the connection's `timezone`.

### Loki compatibility

Verified by container tests on Loki 2.6.1 and 3.6.0 and on live stands running 2.6.1
(k8s) and 3.5 (docker). Only `query_range`, `query`, `labels`, `label/<name>/values` and
`series` are used. On Loki 2.6.1 an empty `/series` or `/label/<name>/values` result
without a `data` field is accepted as an empty list; on 3.x `detected_level` from
structured metadata counts as the line's level (`unknown` as no level). Loki's pattern API
is not needed: `summarizeLogs` groups locally. `summarizeLogs` makes one more request for
the start and stop lines of the same streams, filtered by a literal alternation first so
that a day of a busy environment stays within seconds on 2.6.1; if it fails, the summary
is printed without that block.

## Connecting an MCP client

Claude Code:

```bash
claude mcp add --scope user loki -e LOKI_MCP_CONNECTIONS_FILE=C:/path/connections.json -- java -jar C:/path/loki-mcp-server.jar
```

Codex (`~/.codex/config.toml`):

```toml
[mcp_servers.loki]
command = "java"
args = ["-jar", "C:/path/loki-mcp-server.jar"]
env = { LOKI_MCP_CONNECTIONS_FILE = "C:/path/connections.json" }
```

Any other client: the command `java -jar loki-mcp-server.jar`, transport stdio. stdout
belongs to JSON-RPC; the server's own logs go to stderr and to `~/.loki-mcp-server/logs`.
The log has the connections at start-up, one line per tool call (arguments, result, size,
time) and one per Loki request (path, parameters, status, time); URLs, credentials and log
line contents never appear there.

## How to ask

Name the stand, the service and the period: "show errors of ssj-ui-backend on DEV for the
last hour", "the DEV stand is broken, find out why", "what values does the label
applicationName have on TST". The typical investigation the server suggests to the model
through `instructions`:
`listConnections → discoverLogs → countLogs → summarizeLogs → queryLogs → getLogContext → queryLogs raw=true`.

## Error format

An error is the text `Error <CODE>: <what is wrong and what to do>` with `isError=true`,
never a transport exception. Examples:

| Code | When | What to do |
|---|---|---|
| `CONNECTION_REQUIRED`, `UNKNOWN_CONNECTION` | missing or wrong `connection` name | take the name from `listConnections` |
| `INVALID_ARGUMENT` | unparseable time, `limit` out of range, window longer than `maxIntervalSeconds`, a metric query in `queryLogs`, a pipeline in `getLogContext` | the message names the accepted format or the right tool |
| `UPSTREAM_BAD_REQUEST` | Loki rejected the LogQL | Loki's text is passed on (`Loki rejected the query: parse error at line 1, col 23: …`) — fix the query from it |
| `UPSTREAM_TIMEOUT`, `UPSTREAM_UNAVAILABLE`, `UPSTREAM_RATE_LIMITED` | no answer within `requestTimeoutMs`, 5xx, 429 | narrow the window or the query, retry later |
| `UPSTREAM_UNAUTHORIZED`, `UPSTREAM_FORBIDDEN`, `ENDPOINT_UNAVAILABLE` | 401/403/404 from Loki or an ingress | check `auth`/`tenant`; a 404 on one endpoint does not mean the others are unavailable |
| `RESPONSE_BUDGET_EXCEEDED` | not even a minimal response fits `maxResponseBytes` | narrow the query or raise the connection limit |

URLs, credentials, tenant and Loki texts other than query errors never appear in messages.
The full list of codes is in [docs/queries.md](docs/queries.md#errors) and
[docs/http-client.md](docs/http-client.md).

## Troubleshooting

- **The server does not start / the client shows "failed".** Check the client's stderr or
  `~/.loki-mcp-server/logs/loki-mcp-server.log`: an invalid `connections.json` (unknown
  field, duplicate key, unset `${VARIABLE}`) stops the start-up with a message that carries
  neither the original text nor credentials. Java 21+ must be on the client's `PATH`.
- **`ENDPOINT_UNAVAILABLE` (404).** An ingress may close part of Loki's paths (on the asva2
  DEV stand only the read paths `query`, `query_range`, `labels`, `label/<name>/values`,
  `series` are exposed; `/config`, `tail`, `push` answer 404). The server uses read paths
  only, so a 404 on a live stand usually means a wrong path prefix in `url` or an ingress
  closed for one endpoint; the other tools keep working.
- **`UPSTREAM_TIMEOUT`.** A structured-metadata or `| json` filter over a day of a busy
  stand may not fit into `requestTimeoutMs`: narrow the window (`start="now-3h"`), add a
  label to the selector or lower `limit`/`sample`.
- **Empty response.** `no matching lines` in the last hour is not proof that there are no
  errors: quiet stands (asva2 TST) need `start="now-6h"`/`"now-24h"`; check the labels with
  `discoverLogs` — a service may have no `level` label if it was not restarted after the
  logging change; then filter by text (`|= "ERROR"`) or by `detected_level` on Loki 3.x.
- **A label is missing for a service.** `applicationName` ≠ container name and some streams
  lack it; the connection's `hint` should name fallback labels (`instance`, `container`),
  and `discoverLogs` with `label="applicationName"` shows who has it.
- **Lines without a level (`-` in the second column).** Stack trace continuation lines of
  services that log line by line have no `level`; they are not hidden, and `getLogContext`
  by a selector without `level` shows them around the error.
- **Truncated response.** `Output limit reached` in the footer means the `maxResponseBytes`
  limit: narrow the query, use `countLogs`/`summarizeLogs` or raise the connection limit.
- **What exactly went to Loki.** The server log has one line per request with the path and
  parameters (`GET /loki/api/v1/query_range {start=…, query=…} -> 200, 1586 bytes, 397 ms`)
  and one per tool call; host, auth and line contents are never written there.

## Migrating from mcp-loki

Tool and setting mapping: [docs/migration-from-mcp-loki.md](docs/migration-from-mcp-loki.md).

## Development

```powershell
.\gradlew.bat build              # unit tests and the stdio smoke on the packaged jar
.\gradlew.bat integrationTest    # Loki 2.6.1 and 3.6.0 in Testcontainers (needs Docker)
python scripts/live_smoke/run_smoke.py --connection dev   # read-only run of the jar against a live stand
```

The live smoke takes profiles from [examples/connections.json](examples/connections.json)
(URLs from `LOKI_DEV_URL`/`LOKI_TST_URL`), starts the jar over stdio like a real client and
walks `listConnections → discoverLogs → countLogs → queryLogs → summarizeLogs →
getLogContext → raw`, plus pipeline rejection and a Loki parser error; the
selector is taken from the `discoverLogs` response, so the script does not depend on a
particular stand. `--verbose` prints full responses, `--window now-24h` widens the window.
Nothing is written to Loki.

CI ([.github/workflows/build.yml](.github/workflows/build.yml)): `gradlew build` and
`integrationTest` on every push/PR, a GitHub Release with the jar on a `v*` tag.
Contributor rules: [CONTRIBUTING.md](CONTRIBUTING.md), security: [SECURITY.md](SECURITY.md).

Design decisions and open items: [docs/decisions.md](docs/decisions.md); agent rules:
[AGENTS.md](AGENTS.md).
