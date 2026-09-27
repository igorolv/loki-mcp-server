# Connections and local line formats

The server loads a JSON file at ~/.loki-mcp-server/connections.json, or LOKI_MCP_CONNECTIONS_FILE. It is read once at startup, strictly and without network probes. The file, URLs, credentials, tenant and parser exception text are never returned to the model or written to diagnostics.

~~~json
{
  "exportRoots": ["exports"],
  "connections": {
    "dev": {
      "description": "Development stand",
      "hint": "Start with {app=\"backend\"}; use discoverLogs for current app values.",
      "url": "${LOKI_DEV_URL}",
      "timezone": "Europe/Moscow",
      "serviceLabels": ["app", "container"],
      "formatFile": "java-formats.json"
    }
  }
}
~~~

At least one connection is required. Names are case sensitive, 1–64 ASCII letters, digits, dot, dash or underscore, starting with a letter or digit. Every data call passes one name explicitly; there is no default even with one connection. listConnections shows each name, description (up to 512 characters), hint (up to 1024) and a short line of effective limits: the separate discoverLogs windows for unscoped labels/values and match, the windows for queryLogs, countLogs totals/label groups, countLogs time-only buckets and exportLogs, plus the queryLogs line cap and one-request timeout. Durations use d, h, m, s or ms. It does not show the URL, authentication, tenant or full configuration. Put stand specific selectors and field advice in the hint. serviceLabels defaults to service_name, service, app, container, job and names the labels tried in order when a compact line needs a service name. timezone defaults to UTC.

url is an absolute HTTP/HTTPS URL and may include a path prefix. URL user info, query and fragment are forbidden. auth is omitted or one of:

~~~json
{"type":"NONE"}
{"type":"BASIC","username":"reader","password":"${LOKI_PASSWORD}"}
{"type":"BEARER","token":"${LOKI_TOKEN}"}
~~~

tenant, when set, is sent as X-Scope-OrgID. ${VARIABLE} substitution is supported in url, credentials, tenant, formatFile and exportRoots. It happens once without shell evaluation; an unset or malformed variable stops startup. Do not commit real credentials or internal addresses.

## Export roots

exportRoots is an optional list of up to 16 directories at the top level. Relative paths resolve against the connections file. If omitted, the only root is <data dir>/exports, where the data dir defaults to ~/.loki-mcp-server and LOKI_MCP_DATA_DIR can change it. Directories are created on first export. An export destination must stay within a root after normalization and symbolic link resolution; existing files are never overwritten.

## Format file

formatFile is one optional path to a JSON object with plain-line parsing formats and an optional framePattern. A relative path resolves against the connections file. The file is loaded once per path and is limited to 1 MiB. An example is [java-formats.json](../examples/java-formats.json).

~~~json
{
  "formats": [
{"id": "simple", "pattern": "^(?<level>INFO|ERROR) (?<message>.*)$"}
  ]
}
~~~

formats are Java regular expressions searched on the first line of a non JSON event. A named message group is required. Named level, logger, service or application groups become the corresponding line fields; other groups become fields available to export templates. The first matching format wins. IDs are lower case letters, digits and dashes, unique within the file; at most 32 formats. An optional top-level `framePattern`, such as `"^\\s+at\\s+.+$"`, matches a complete standalone line for compact queryLogs folding. It does not change raw previews or exports. Invalid or oversized regular expressions stop startup.

## Export template

The format argument of exportLogs is raw by default or an inline template. {time} is the event time in the connection timezone; {time:HH:mm} chooses a Java time pattern. {level}, {service}, {logger}, {message}, {traceId}, {stack} and {line} are built in. Other placeholders read a dotted JSON field, a plain format group, a stream label or structured metadata. {a|b} chooses the first present field; {level:5} pads left and {logger:-40} pads right; {{ and }} are literal braces. A missing value is empty. An unrecognised plain line is written unchanged unless the template includes {line}. An invalid template returns INVALID_ARGUMENT.

## Limits

All fields in a connection's limits object are optional:

| Field | Default | Meaning |
|---|---:|---|
| connectTimeoutMs | 5000 | HTTP connection timeout |
| requestTimeoutMs | 30000 | One Loki request timeout |
| maxHttpResponseBytes | 8388608 | One Loki response body |
| maxResponseBytes | 65536 | One MCP response; minimum 1024 |
| maxEntries | 1000 | Maximum query page |
| maxIntervalSeconds | 86400 | Maximum queryLogs and exportLogs time window |
| maxCountIntervalSeconds | 86400 | Maximum countLogs window for totals, labels and label/time grouping |
| maxTimeCountIntervalSeconds | 604800 | Maximum countLogs window for time-only buckets |
| maxDiscoveryIntervalSeconds | 604800 | Maximum discoverLogs time window without match |
| maxSeriesIntervalSeconds | 86400 | Maximum discoverLogs time window with match |
| maxExportLines | 500000 | Export stop after this many lines |
| maxExportBytes | 268435456 | Export stop after this many bytes |
| maxExportDurationMs | 25000 | Total exportLogs duration; a request uses only the remaining time |

Every limit must be a positive integer; maxResponseBytes must be at least 1024. The tool text budget reserves 512 bytes of maxResponseBytes for the JSON-RPC envelope. A file over 1 MiB, duplicate keys, unknown fields, wrong types or invalid values stop startup. The error is a safe CONFIGURATION_ERROR without source text. The [example connection file](../examples/connections.json) shows several stands.
