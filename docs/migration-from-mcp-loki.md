# Migrating from mcp-loki

`mcp-loki` (lexfrei) is one process per Loki, configured through environment variables,
answering with Loki's raw JSON. This server is one process for several stands from
`connections.json`, answering with text for a small model. Below is what maps to what and
what changes in the client configuration and in the model's habits.

## Configuration

| mcp-loki | loki-mcp-server |
|---|---|
| `LOKI_URL` | `connections.<name>.url` (`${LOKI_DEV_URL}` is fine) |
| `LOKI_USERNAME` / `LOKI_PASSWORD` | `auth: {"type":"BASIC","username":…,"password":"${…}"}` |
| `LOKI_TOKEN` | `auth: {"type":"BEARER","token":"${…}"}` |
| `LOKI_ORG_ID` | `tenant` |
| `MCP_HTTP_PORT` (SSE) | none: stdio only |
| one server per stand | one server, several connections; every tool takes `connection` |
| — | `timezone`, `hint`, `serviceLabels`, `limits` — see [connections.md](connections.md) |

The MCP client entry changes from `podman run … ghcr.io/lexfrei/mcp-loki` to
`java -jar loki-mcp-server.jar` with `LOKI_MCP_CONNECTIONS_FILE` (README, "Connecting an
MCP client"). With several stands the separate `loki-dev`, `loki-tst` servers become one
`loki`, and the stand is chosen by connection name.

## Tools

| mcp-loki | loki-mcp-server | Differences |
|---|---|---|
| `loki_query` (log query) | `queryLogs(connection, query, start, end, limit, raw)` | lines print in chronological order as `HH:mm:ss.SSS LEVEL service message` in the connection's timezone; stack traces are compacted; `raw=true` gives the original line with stream labels. No `direction`: always the newest lines of the window; older ones by repeating with `end` from the footer |
| `loki_query` (metric query) | none | arbitrary metric LogQL is not offered; `countLogs` covers totals, a breakdown by label and counts over time |
| `loki_query` used for counting | `countLogs(connection, query, start, end, groupBy)` | the server builds the metric expression; `groupBy="time"` gives buckets with spike markers |
| `loki_labels` (no `name`) | `discoverLogs(connection)` | label names together with values, line format, JSON fields and a ready-made selector |
| `loki_labels` (`name=app`) | `discoverLogs(connection, label="app")` | up to 200 values, alphabetically |
| `loki_series` | `discoverLogs(connection, selector=…)` | labels and values of the streams matching the selector; the streams themselves are not listed |
| `loki_stats` | none | `countLogs` gives the line count; chunk volume is not needed for an investigation |
| `loki_ready`, `loki_config` | none | the server does not expose `/ready` or `/config`; a transport error arrives as text on the first call |
| — | `summarizeLogs(connection, query, start, end, sample)` | groups of repeated messages in a sample |
| — | `getLogContext(connection, selector, time, before, after)` | lines around a moment in a stream |

Time formats are compatible: `now`, relative `1h`/`now-1h`, RFC3339; local time in the
connection's timezone and epoch nanoseconds are added. The `limit` cap is the connection's
`maxEntries` (default 1000), the window cap is `maxIntervalSeconds` (24h).

## Prompts

`error_logs`, `rate_query` and `top_label_values` were not carried over: the tool
descriptions and the server `instructions` play their role (the flow `listConnections →
discoverLogs → countLogs → summarizeLogs → queryLogs → getLogContext → raw`).
`error_logs(app)` is `countLogs {applicationName="app", level="error"}` followed by
`queryLogs`; `top_label_values` is `countLogs` with `groupBy="<label>"`; `rate_query` is
`countLogs` with `groupBy="time"`.

## What changes for the model

- Responses are text, not JSON: no parsing of `data.result[].values` and nanoseconds.
- `count` in `mcp-loki` counted streams; `countLogs` counts lines.
- Incompleteness is visible as one phrase (`newest 50 of more`, `Output limit reached`);
  continuation is a repeat with `end`, there are no cursors.
- A LogQL error arrives as Loki's text (`Loki rejected the query: parse error …`); other
  errors as a short code with advice; URLs and credentials never appear in them.
- Stand instructions (`hint`) are read from `listConnections`, not from a separate document.

## Migration steps

1. Build the jar (`gradlew bootJar`) and prepare `connections.json` after
   [examples/connections.json](../examples/connections.json); URLs and credentials through
   environment variables.
2. Check the stand with the read-only run: `python scripts/live_smoke/run_smoke.py --connection <name>`.
3. Replace the `mcp-loki` entry in the client configuration with `loki-mcp-server`.
4. Update the project instructions for the model: a connection name and the flow from
   README "How to ask" instead of LogQL recipes for `loki_query`. Changing the asva2
   documents is a separate step.
