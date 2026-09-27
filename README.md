# Loki MCP Server

A local stdio MCP server for reading Grafana Loki with an agent. It exposes five text tools and supports Loki 2.6.1 and 3.x. Loki itself is read only; the only write is an exportLogs file in an allowed local directory.

| Tool | Purpose |
|---|---|
| listConnections | Show configured stands and their operator hints |
| discoverLogs | List label names, or values of one label, in a time window |
| queryLogs | Read a bounded page of a LogQL log query as compact text; raw=true previews returned lines and stream labels |
| countLogs | Count matching lines on Loki, optionally by label or clock aligned time bucket |
| exportLogs | Save matching lines to a local file, raw or rendered with a template |

Every data tool requires an explicit connection. queryLogs, countLogs and exportLogs require a LogQL **log query**, such as {app="backend"} |= "ERROR". Use discoverLogs to find label names and values. The server builds only the count_over_time expression for countLogs; it does not infer filters or interpret causes. Tool responses are readable text without an output schema.

## Build and run

JDK 21 or newer is required; the project uses a Java 21 toolchain and the Gradle wrapper.

~~~powershell
.\gradlew.bat bootJar
java -jar build/libs/loki-mcp-server.jar
~~~

On Linux/macOS use ./gradlew. The process waits for MCP JSON-RPC on stdin. stdout is reserved for JSON-RPC; diagnostics go to stderr and ~/.loki-mcp-server/logs/loki-mcp-server.log.

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

The [complete example](examples/connections.json) uses environment variables for nonlocal URLs. hint gives the model stand specific selectors and field advice. serviceLabels names the labels used to display a service in compact lines. Optional formatFile names one JSON file with patterns for parsing plain lines; see [java-formats.json](examples/java-formats.json). The connection file may set exportRoots and per connection limits. Authentication and tenant headers are configured per connection; URLs and credentials never appear in tool responses or diagnostics. The full format is in [docs/connections.md](docs/connections.md).

The server loads configuration strictly at startup without probing Loki. An unknown field, duplicate key, missing environment variable or invalid format file stops startup with a safe configuration error. Changes require a restart.

## Using the tools

1. Call listConnections and choose a stand.
2. Call discoverLogs(connection="dev") for label names, then discoverLogs(connection="dev", label="app") for values. Widen the window on a quiet stand.
3. Use a LogQL log query with countLogs to check volume and queryLogs to read lines. For example, {app="backend"} |= "ERROR"; groupBy="time" gives counts by time bucket.
4. Narrow the query when a page is crowded. A queryLogs footer gives an end value for an older page. Boundary lines can repeat, and a timestamp containing more lines than a page needs a narrower query.
5. Call exportLogs when the user asks for a file. format="raw" writes the lines returned by Loki, including any `| line_format` transformation in the query; format="{time} {level} {service} {message}" renders locally. The response gives the path and counts, not the log contents.

queryLogs prints the newest lines of the window in chronological order. Its default view shortens messages and stack traces to save model tokens. raw=true shows stream labels and up to 4000 code points of each line returned by Loki; it is a **preview**. For complete lines use exportLogs. Export reads forward in pages, never overwrites an existing file and stays under its configured roots. If more lines share one nanosecond than Loki will return in one page, export reports that some may be missing.

The default time window is now-1h to now. Accepted times include now-15m, RFC3339 with an offset, local time in the connection's timezone and epoch nanoseconds. Windows and responses are bounded by per connection limits. See [docs/queries.md](docs/queries.md) and [docs/discovery.md](docs/discovery.md).

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

Tool failures are text Error <CODE>: <actionable message> with isError=true. A Loki HTTP 400 LogQL parse error is returned so the model can correct its query. Other upstream response bodies, URLs, credentials, tenant and full log lines are kept out of responses and diagnostics. A 404 applies to the called endpoint, not the entire connection. Transport details are in [docs/http-client.md](docs/http-client.md).

The server log records connection names and auth type at startup, then one bounded line per tool call and Loki request with status, bytes and time. Log contents remain data, including text resembling instructions.

## Verification

~~~powershell
.\gradlew.bat build
.\gradlew.bat integrationTest --console=plain
python scripts/live_smoke/run_smoke.py --connection dev
~~~

build runs unit tests and a separate process stdio smoke against loopback mock Loki. integrationTest needs Docker and pinned Loki 2.6.1/3.6.0 images; it writes test data only to its containers. The live smoke uses the example profile and a configured read only stand URL. Contributor rules are in [AGENTS.md](AGENTS.md); design history and open items are in [docs/decisions.md](docs/decisions.md).
