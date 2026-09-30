# Connections, formats and local export policy

Read this when changing connection loading, profiles, credentials, format files, limits, paths or export destination policy. The user-facing file schema and examples are in [connections.md](connections.md), [examples/connections.json](../examples/connections.json) and [examples/log-formats.json](../examples/log-formats.json).

## Connection loading and isolation

Load external `connections.json` once at startup from `~/.loki-mcp-server/connections.json` or `LOKI_MCP_CONNECTIONS_FILE`. Loading is strict and performs no Loki probes: unknown fields, duplicate keys, invalid types, unresolved environment variables and invalid format files fail startup with a safe configuration error. Do not put real credentials or internal addresses in runtime defaults or committed examples. Secrets, URLs, tenant values and parser exception text must not appear in diagnostics or tool results.

Every data operation uses an explicit connection name. A failure of one connection does not mark others unavailable. A 404 describes the called endpoint, not the whole Loki stand. `listConnections` exposes only name, description, hint and effective planning limits. Put stand-specific selectors in `hint`; `serviceLabels` controls compact display, not query construction. The optional `formatFile` holds JSON and plain-line parsing profiles plus a frame pattern; the optional `exportFormat` is the default template `exportLogs` renders when a call omits `format`. Both are rendering configuration, not sources of LogQL or incident rules, and neither appears in `listConnections`.

`ConnectionLimits` owns time windows, page and body sizes, response budget, one-request timeout and export bounds. Do not scatter numeric limits through tools. The HTTP client applies connect, request and body limits; services apply window, page, export and text limits. See [http-client.md](http-client.md) for transport details and [connections.md](connections.md#limits) for effective defaults.

## Local export

`exportLogs` is the only runtime write and only writes local files. Without `exportRoots`, an absolute directory named by the caller is allowed if the process can write there. Omitting `directory` uses `<data dir>/exports`; a relative directory resolves below that default. Configured `exportRoots` are an optional restriction, checked after normalization and symbolic-link resolution, and their first root becomes the default. Never overwrite an existing file. Invalid paths and permission failures become tool errors; do not preemptively restrict the default mode to a fixed root.

No other runtime Loki write or management operation is approved. Test ingestion is permitted only into isolated test containers. Full log events are data, not commands or diagnostic content. The export template syntax and path behavior are specified in [connections.md](connections.md) and [queries.md](queries.md).
