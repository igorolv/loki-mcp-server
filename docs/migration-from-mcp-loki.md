# Migrating from mcp-loki

This server is a local stdio MCP reader for Loki 2.6.1 and 3.x. It uses Loki's read endpoints directly and has no push, delete, management or Grafana proxy tools. Configure at least one named connection in an external connections.json and pass connection on every data call.

| Earlier action | This server |
|---|---|
| List labels | discoverLogs(connection), then discoverLogs(connection, label="app") for values |
| Query log lines | queryLogs(connection, query="{app=\"backend\"} |= \"ERROR\"") |
| Count matching lines | countLogs with the same LogQL log query; optional groupBy label or time |
| Download a window | exportLogs with the same query and format="raw" or a layout/template |
| Choose a server | listConnections; every data call names one connection |

queryLogs returns compact chronological text by default. raw=true previews the original line with result stream labels but cuts long lines at 4000 code points; exportLogs writes full lines to a local file. The model supplies LogQL rather than service, level or text parameters. The connection hint can show selectors useful on that stand.

The public interface has no metric-query tool, arbitrary Loki endpoint access, cursors, cached events, structuredContent or analysis tool. Earlier summaries, context lookup, automatic empty-result diagnosis, system aliases and rule-based causes were removed in the five-tool simplification of 2026-09-27. See [decisions.md](decisions.md) and the current [queries.md](queries.md).
