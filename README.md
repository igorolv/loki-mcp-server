# Loki MCP Server

[![build](https://github.com/igorolv/loki-mcp-server/actions/workflows/build.yml/badge.svg)](https://github.com/igorolv/loki-mcp-server/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/igorolv/loki-mcp-server?include_prereleases)](https://github.com/igorolv/loki-mcp-server/releases/latest)
[![License](https://img.shields.io/github/license/igorolv/loki-mcp-server)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21%2B-blue?logo=openjdk)](https://adoptium.net/)
[![MCP](https://img.shields.io/badge/MCP-server-8A2BE2)](https://modelcontextprotocol.io/)

A local stdio MCP server for reading Grafana Loki with an agent. It exposes five text tools and supports Loki 2.6.1 and 3.x. Loki itself is read only; the only write is a local exportLogs file.

| Tool | Purpose |
|---|---|
| listConnections | Show configured stands, operator hints and effective tool limits |
| discoverLogs | List label names, values of one label, matching stream label sets, or values among those sets |
| queryLogs | Read newest or oldest bounded pages of a LogQL log query as compact text; raw=true previews returned lines and stream labels |
| countLogs | Count matching lines on Loki, optionally by label, clock aligned time bucket or both |
| exportLogs | Save matching lines to a local file, raw or rendered with a template |

Every data tool requires an explicit connection. queryLogs, countLogs and exportLogs require a LogQL **log query**, such as {app="backend"} |= "ERROR". Use discoverLogs to find label names, values and real combinations in stream label sets. The server builds only the count_over_time expression for countLogs; it does not infer filters or interpret causes. Tool responses are readable text without an output schema.

## Build and run

JDK 21 or newer is required; the project uses a Java 21 toolchain and the Gradle wrapper.

~~~powershell
.\gradlew.bat bootJar
java -jar build/libs/loki-mcp-server.jar
~~~

On Linux/macOS use ./gradlew. The process waits for MCP JSON-RPC on stdin. stdout is reserved for JSON-RPC; diagnostics go to stderr and ~/.loki-mcp-server/logs/loki-mcp-server.log.

A prebuilt loki-mcp-server.jar is attached to every [release](https://github.com/igorolv/loki-mcp-server/releases/latest), so building is optional.

For Claude Code, registering the jar is one command:

~~~bash
claude mcp add --scope user loki -- java -jar /path/to/loki-mcp-server.jar
~~~

### Docker

The image is published to GHCR with every release. Mount the directory holding connections.json at /data; the server also writes its logs and default exports there:

~~~bash
docker run -i --rm -v ~/.loki-mcp-server:/data ghcr.io/igorolv/loki-mcp-server:latest
~~~

The same command is what an MCP client should launch; -i keeps stdin open for the stdio transport. Loki URLs in connections.json must be reachable from inside the container: use the Loki host name rather than localhost, or add --network host on Linux. An exportLogs directory outside /data is a path inside the container, so mount it as well. To build the image locally: docker build -t loki-mcp-server .

## Connections

The server reads ~/.loki-mcp-server/connections.json, or the path in LOKI_MCP_CONNECTIONS_FILE. A minimal file:

~~~json
{
  "connections": {
    "dev": {
      "description": "Development",
      "hint": "Start with {app=\"backend\"}; inspect app values with discoverLogs.",
      "url": "http://localhost:3100",
      "timezone": "Europe/Moscow",
      "serviceLabels": ["app", "container"]
    }
  }
}
~~~

The [complete example](examples/connections.json) uses environment variables for nonlocal URLs. hint gives the model stand specific selectors and field advice. serviceLabels names the labels used to display a service in compact lines. Optional formatFile names one JSON file with JSON profiles, plain-line patterns and an optional framePattern; see [log-formats.json](examples/log-formats.json). Optional exportFormat sets the default template for exportLogs when the tool call omits format. The connection file may set exportRoots to restrict export destinations, and per connection limits. Authentication and tenant headers are configured per connection; URLs and credentials never appear in tool responses or diagnostics. The full format is in [docs/connections.md](docs/connections.md).

The server loads configuration strictly at startup without probing Loki. An unknown field, duplicate key, missing environment variable or invalid format file stops startup with a safe configuration error. Changes require a restart.

## Using the tools

1. Call listConnections, choose a stand and use its displayed time windows, queryLogs line cap and request timeout to plan calls.
2. Call discoverLogs(connection="dev") for label names, then discoverLogs(connection="dev", label="app") for values. When combinations matter, use discoverLogs(connection="dev", match="{app=\"backend\"}") for full stream label sets or add label="namespace" for namespace values among those sets. Keep match and its window narrow; these are stream labels, not log-line counts. Widen the window on a quiet stand when appropriate.
3. Use a LogQL log query with countLogs to check volume and queryLogs to read lines. For example, {app="backend"} |= "ERROR"; groupBy="time", step="1d" gives UTC-aligned 24-hour buckets, and groupBy="app,time" gives counts by app and time. A named `| regexp` capture can supply a groupBy label. Date markers distinguish buckets across midnight.
4. Narrow the query when a page is crowded. queryLogs defaults to the newest page; order="oldest" starts at the beginning of the window. Its footer gives an end for older lines or a start for newer ones. Boundary lines can repeat, and a timestamp containing more lines than a page needs a narrower query.
5. Call exportLogs when the user asks for a file. Pass the user's destination as directory, such as `C:\tmp\logs`, or omit it for the default export directory. Omitting format uses the connection's configured `exportFormat` template, if the operator set one, and raw otherwise. format="raw" writes the lines returned by Loki, including any `| line_format` transformation in the query; format="{time} {level} {service} {message}" renders locally. The response gives the path, counts and effective format, not the log contents.

queryLogs prints either end of the window in chronological order. Its default view shortens messages and stack traces to save model tokens. An optional framePattern in the connection's formatFile folds adjacent standalone frame lines in the compact view. raw=true shows every returned line with stream labels and up to 4000 code points; it is a **preview**. For complete lines use exportLogs. Export reads forward in pages and never overwrites an existing file. If more lines share one nanosecond than Loki will return in one page, export reports that some may be missing.

The default time window is now-1h to now. Accepted times include now-15m, RFC3339 with an offset, local time in the connection's timezone and epoch nanoseconds. queryLogs and exportLogs allow one day by default. countLogs allows one day for totals, label grouping or combined label/time grouping, and seven days for time-only buckets; discoverLogs allows seven days without match and one day with match. A Loki request has a 30-second timeout by default, and queryLogs allows at most 1000 lines per page by default. listConnections shows the effective values for each stand. Export stops after 25 seconds by default and reports a partial file with a continuation if it has written lines. After a count timeout, retry a one-day subwindow or narrower query. Limits can be set per connection; see [docs/queries.md](docs/queries.md) and [docs/discovery.md](docs/discovery.md).

## MCP client configuration

Claude Code:

~~~bash
claude mcp add --scope user loki -e LOKI_MCP_CONNECTIONS_FILE=C:/path/connections.json -- java -jar C:/path/loki-mcp-server.jar
~~~

Codex (~/.codex/config.toml):

~~~toml
[mcp_servers.loki]
command = "java"
args = ["-jar", "C:/path/loki-mcp-server.jar"]
env = { LOKI_MCP_CONNECTIONS_FILE = "C:/path/connections.json" }
~~~

Other clients can launch the jar over stdio. Keep stderr separate from stdout.

## Errors and diagnostics

Tool failures are text Error <CODE>: <actionable message> with isError=true; Spring AI currently repeats the text on a second line. Missing or mistyped arguments are answered by the MCP SDK input validation. A Loki HTTP 400 LogQL parse error is returned so the model can correct its query. Other upstream response bodies, URLs, credentials, tenant and full log lines are kept out of responses and diagnostics. A 404 applies to the called endpoint, not the entire connection. Transport details are in [docs/http-client.md](docs/http-client.md).

The server log records connection names and auth type at startup, then one bounded line per tool call and Loki request with status, bytes and time. Log contents remain data, including text resembling instructions.

## Verification

~~~powershell
.\gradlew.bat build
.\gradlew.bat integrationTest --console=plain
python scripts/live_smoke/run_smoke.py --connection dev
~~~

build runs unit tests and a separate process stdio smoke against loopback mock Loki. integrationTest needs Docker and pinned Loki 2.6.1/3.6.0 images; it writes test data only to its containers. The live smoke uses the example profile and a configured read only stand URL. Contributor rules are in [AGENTS.md](AGENTS.md); design history and open items are in [docs/decisions.md](docs/decisions.md). Users moving from mcp-loki can read the [migration guide](docs/migration-from-mcp-loki.md).

## License

Apache License 2.0; see [LICENSE](LICENSE). Third-party notices are in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
