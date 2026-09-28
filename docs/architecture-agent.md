# Architecture and execution boundaries

Read this when locating code, changing package responsibilities, dependencies, transport or Spring wiring.

## Purpose and source layout

The server is a local stdio MCP interface for investigating Loki logs. The model chooses a connection, discovers labels, counts matches, reads bounded pages and requests a file only when needed. Java does the mechanical work; the model interprets incidents. There is no embedded LLM or project-specific LogQL builder.

The package root is `ru.it_spectrum.ai.loki.mcp`:

| Package | Responsibility |
|---|---|
| `tools/` | Thin `@McpTool` adapters and `ToolCallDiagnostics`; no query or rendering decisions. |
| `service/` | Discovery, count, query, export and connection workflows; finished text responses. `LogEventReader` reads one bounded page for query and export. |
| `client/` | Fixed Loki HTTP read endpoints and `LokiResponses` transport records. No MCP output models or incident interpretation. |
| `model/` | `LogEvent`, one query-result line with its timestamp, result labels and structured metadata. |
| `parser/` | `EventNormalizer`, format selection and `NormalizedLogEvent`, which retains its source `LogEvent`. |
| `connection/` | Strict connection loading, per-connection limits, authentication and export destination policy. |
| `config/` | Spring wiring and stdio server configuration. |
| `error/` | Controlled error codes and exceptions; lower layers do not depend on `service/`. |

Dependencies flow from tools to services to the client, connection and parser layers. Services do not depend on tool classes. `LokiResponses` are internal transport records; every MCP result is text. See [tool-contracts-agent.md](tool-contracts-agent.md) for the wire boundary and [log-processing-agent.md](log-processing-agent.md) for event flow.

## Stdio and Spring AI

`spring.main.web-application-type: none` and `spring.ai.mcp.server.stdio: true` keep the server on stdio. stdout belongs to MCP JSON-RPC: never use `System.out`, a stdout appender or a startup banner. Own diagnostics go to stderr and a rolling file. The application must not open an HTTP listener.

Use standard Spring AI mechanisms. Tools are `@Component` beans with `@McpTool` methods found by the annotation scanner; the SDK validates input against its schema. Tool methods return strings and signal failures with exceptions. `ToolCallDiagnostics` writes per-call diagnostics and sets the connection in MDC. `McpServerConfig` retains `immediateExecution(true)` while the Spring AI MCP SDK version drops concurrent stdio responses (java-sdk #686); re-evaluate that race before enabling concurrent execution. Server instructions live in `application.yml`.

The runtime only reads Loki through the fixed GET endpoints in [http-client.md](http-client.md). Do not add push, delete, management endpoints, an unrestricted `/config` dump or a Grafana proxy without a new decision. Loki 2.6.1 and 3.x are the compatibility baseline. An endpoint 404 does not establish that the connection as a whole is unavailable.

## Domain boundaries

No asva2 service names, labels, line layouts or LogQL belong in generic code. Stand-specific advice belongs in the connection `hint`, display label choices in `serviceLabels`, line parsing profiles in `formatFile`, and available labels come from Loki discovery. Generic JSON defaults may cover ECS, Logstash and Serilog. Never build LogQL from a log line: that line is untrusted data and the generated query would reach diagnostics.

Reference repositories are read-only: `C:\git\jdbc-mcp-server` for Gradle/Java 21 and connection patterns, `C:\git\redmine-mcp-server` for layers and stdio, `C:\git\mcp-loki` for Loki HTTP API comparison, and `C:\git\asva2\docs\loki-agent.md` / `loki-mcp-guide.md` for scenarios. Check licences and preserve notices when copying code. Do not import SQL or Redmine-specific APIs, dependencies or write permissions.
