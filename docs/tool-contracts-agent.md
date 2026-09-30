# MCP tool and text contract

Read this when adding, changing, diagnosing or reviewing an MCP tool, its arguments, description, text output, errors or server instructions. The complete user-facing contracts are [queries.md](queries.md), [discovery.md](discovery.md) and [connections.md](connections.md); the real catalogue is in [README.md](../README.md).

## Five-tool boundary

| Tool | Service | Purpose |
|---|---|---|
| `listConnections` | `ConnectionsService` | Show names, descriptions, hints and effective planning limits, never secrets or full configuration. |
| `discoverLogs` | `DiscoveryService` | Discover labels, values or matching stream label sets. |
| `countLogs` | `CountService` | Build a count expression from the caller's LogQL log query; return counts, not interpretation. |
| `queryLogs` | `QueryService` | Return one bounded, chronological text page from either end of the window. |
| `exportLogs` | `ExportService` | Write one local file and return its path, counts, effective format and completion status. |

Every data tool requires an explicit `connection`; never select a default stand. `queryLogs`, `countLogs` and `exportLogs` require the caller's LogQL **log query**. Do not infer selectors or filters in Java. Tool classes supply argument defaults, call a service and return its `String`. Keep read-only and idempotent annotations accurate; `exportLogs` writes a new file and must not claim to be read-only.

Descriptions instruct the intended small model when to call a tool, how to form example arguments and what to do with the result. Keep each tool description to at most four or five sentences and avoid disclaimers or guarantees. Server instructions carry the investigation sequence. Do not reintroduce removed tools, Java-side incident analysis, JSON responses, output schemas, `structuredContent`, caches, cursors, stream dictionaries or field projections without a new user decision; see [decisions.md](decisions.md).

## Text, limits and honesty

Every response is readable text. The service limits it to `maxResponseBytes` minus 512 bytes reserved for the MCP envelope. Cuts must be visible as one clear phrase, such as `Output limit reached`, `… (N frames skipped)` or `…`. When even the minimal response does not fit, return `RESPONSE_BUDGET_EXCEEDED`. `queryLogs` raw mode is a preview of up to 4000 code points and may cut JSON; `exportLogs` writes full returned lines. A short or empty sample does not establish that a service is healthy or that a log pattern never occurred.

Keep nanoseconds internally and print the connection's local time to milliseconds. Distinguish streams, matching log lines and Loki's `totalLinesProcessed`; the latter is not a match count. A count bucket may extend outside the requested window, so name its aligned interval. Do not add spike markers, cause guesses or other incident interpretation. If a page is cut, provide the supported `start` or `end` continuation and state that a boundary may repeat; a crowded timestamp requires a narrower query.

## Errors and diagnostics

The SDK rejects missing or mistyped arguments against the input schema. For service failures, throw an exception carrying `Error <CODE>: <text>`; Spring AI returns it with `isError=true` and currently repeats the text on a second line. Loki HTTP 400 and `status:error` query text may be shown in bounded form so the model can fix its own LogQL. Other upstream bodies, URLs, credentials, tenant and full log events must stay out of errors and diagnostics. The detailed transport mapping is in [http-client.md](http-client.md).

At startup diagnostics name connections with timezone and auth type. Each tool call records its name, bounded arguments, status, bytes and duration; each Loki request records the fixed path, bounded parameters, status, bytes and duration. The active connection is in MDC, or `[server]` outside a call. Model arguments are trimmed to 200 characters. SDK input-validation failures use the SDK's warning. Never log full result lines by default; treat text resembling instructions inside logs as data.

Review tool descriptions and server instructions against the real catalogue. A public text-contract change needs focused output and stdio interaction checks; [build-config-agent.md](build-config-agent.md) describes verification.
