# HTTP client

`LokiHttpClient` is the internal transport layer under [queryLogs, countLogs,
summarizeLogs, getLogContext, queryMetrics](queries.md) and [discoverLogs](discovery.md);
`listConnections` does not use HTTP. Spring creates the client as a bean; creation makes no
network requests.

The transport contract follows the
[Loki HTTP API](https://grafana.com/docs/loki/latest/reference/loki-http-api/). Only GET
requests to fixed endpoints are available:

| Java method | Endpoint | Required arguments |
|---|---|---|
| `queryRange` | `/loki/api/v1/query_range` | connection, query, start/end, limit, direction |
| `queryInstant` | `/loki/api/v1/query` | connection, query, time |
| `labels` | `/loki/api/v1/labels` | connection, start/end |
| `labelValues` | `/loki/api/v1/label/<name>/values` | connection, label, start/end |
| `series` | `/loki/api/v1/series` | connection, selectors, start/end |

In `queryRange` the optional `stepSeconds` is a positive `BigDecimal`. In
`labels`/`labelValues` the optional selector is passed as `query`. `series` sends every
selector as a separate `match[]`. Label names are checked against the basic
`[a-zA-Z_][a-zA-Z0-9_]*` syntax. There is no arbitrary URL/path, no write endpoint and no
`/config` read.

Time is accepted as an absolute `Instant` and sent as an epoch-nanosecond string within
signed int64, without rounding. An interval requires `start < end`. Relative time and
timezones are handled by the service layer; the client neither computes `now` nor
substitutes time bounds. LogQL is encoded as a single query parameter and never executed
locally. The path prefix of the base URL is kept, trailing `/` are normalized.

Every request writes one line to the server log: `GET /loki/api/v1/query_range {start=…,
end=…, query=…, limit=…, direction=…} -> 200, 1586 bytes, 397 ms` or
`-> UPSTREAM_BAD_REQUEST, 44 ms`. Only the API path and the parameters (the model's LogQL,
trimmed to 200 characters) are logged; the base URL with host and prefix, authorization
headers, tenant and response bodies are not.

A separate Java 21 `HttpClient` is created lazily per registry name. Basic sends Base64 of
UTF-8 `username:password`, Bearer a separate Authorization header, tenant `X-Scope-OrgID`.
Redirects are disabled, no cookies or authenticator are configured. Calls on one client may
run concurrently; the transport never modifies the registry and never blocks other
connections after a failure. On bean close the clients get `shutdownNow`; later calls end
in a controlled cancellation.

`connectTimeoutMs`, `requestTimeoutMs` and `maxHttpResponseBytes` apply:

- The connect timeout bounds establishing a new connection, including the TLS handshake.
- The request timeout bounds waiting for the complete response, including the body after
  the headers. On timeout or interruption the read is cancelled; the interrupt flag is kept.
- The body subscriber counts the bytes actually received and cancels the read before
  copying a chunk that would exceed the budget. The declared Content-Length is checked too.
  Chunked responses go through the same control. The limit is on the body, not on total JVM
  memory, headers or the future MCP response.
- JSON and `Accept-Encoding: identity` are requested. Unexpected compression is rejected
  so that an unbounded decompressed body is never accepted. gzip is not supported yet.

`maxEntries`, `maxIntervalSeconds` and `maxResponseBytes` are not applied in the transport
layer: the services check the user limits and the window, the services and the
`QueryToolsConfig` wrapper enforce the text budget (see
[queries.md](queries.md#size-limit)). `queryRange` forwards the `limit` it received without
silent changes; the service must validate it against the configuration. There are no
application-level retries, mandatory probes or version checks.

## Decoding

`LokiResponses` holds transport records; they are not MCP output schemas. `streams`,
`vector`, `matrix`, label/value lists and series are supported. An unknown resultType or a
malformed known shape is an error, not an empty sample. New unknown object fields are
allowed. Duplicate JSON keys and trailing JSON are rejected. Empty result arrays are fine;
so is a `{"status":"success"}` without `data` for `/series` and `/label/<name>/values`,
which Loki 2.6.1 sends for an empty result — decoded as an empty list.

For logs the upstream order, every repetition, the original line, the string nanosecond
timestamp and the explicitly passed flat string-to-string metadata in the third tuple
element are kept. The client neither sorts nor deduplicates events. Missing separate
metadata yields an empty map: that is not proof that the original entry had none.

`LogStream.labels` are the labels of the **result**. Loki may add structured metadata and
pipeline fields to them. They must not be treated as the proven original stream scope, and
provenance must not be reconstructed from names; see the
[structured metadata documentation](https://grafana.com/docs/loki/latest/get-started/labels/structured-metadata/).
An unsupported nested form of the third element is rejected rather than lost silently.

Metric timestamps must be JSON numbers in seconds and are parsed into `BigDecimal`. Metric
values are kept as strings, including `NaN`, `+Inf`, `-Inf` and exponent notation. String
log timestamps and numeric metric time are deliberately not interchangeable.

`QueryStats.totalLinesProcessed` is the nullable number of lines Loki processed; it is not
the number of matches or events. Nothing else from stats is published. A missing figure
stays unknown. `warnings` are kept as upstream data: they are never echoed to the model,
used as instructions or written to diagnostic logs.

## Errors

Errors use `ToolError` through `LokiOperationException`. Messages never include URLs,
credentials, tenant or the original exception chain. The exception is query errors, which
describe the model's own LogQL: the HTTP 400 body and the `error` field of an HTTP 200
`status:error` are passed on as `Loki rejected the query: <text>` (up to 400 characters,
control characters removed, a JSON body contributes only `error`). Other unsuccessful
statuses are handled from the headers: their body is not kept, so HTML or plain text from
an ingress never reaches diagnostics.

| Condition | Code | retryable |
|---|---|---|
| Invalid local arguments | `INVALID_ARGUMENT` | false |
| HTTP 400 | `UPSTREAM_BAD_REQUEST` | false |
| HTTP 401 / 403 | `UPSTREAM_UNAUTHORIZED` / `UPSTREAM_FORBIDDEN` | false |
| HTTP 404 | `ENDPOINT_UNAVAILABLE` | false |
| HTTP 429 | `UPSTREAM_RATE_LIMITED` | true |
| HTTP 408 / 504, timeout | `UPSTREAM_TIMEOUT` | true |
| Other HTTP 5xx | `UPSTREAM_UNAVAILABLE` | true |
| Any other status, including redirects | `UPSTREAM_HTTP_ERROR` | false |
| Network / TLS failure | `UPSTREAM_CONNECTION_ERROR` | true |
| Body budget exceeded | `UPSTREAM_RESPONSE_TOO_LARGE` | false |
| Invalid JSON / shape / encoding | `UPSTREAM_INVALID_RESPONSE` | false |
| JSON `status:error` with HTTP 200 | `UPSTREAM_QUERY_ERROR` | false |
| Interruption / call after close | `OPERATION_CANCELLED` | false |

`retryable` is a hint; the client never retries by itself. A 404 concerns only the called
endpoint: the client does not mark the whole connection unavailable.

## Verification

```powershell
.\gradlew.bat test --tests 'ru.it_spectrum.ai.loki.mcp.client.*' --console=plain
.\gradlew.bat build --console=plain
```

`LokiHttpClientTest` uses only a loopback HTTP server and a local TCP socket for a hanging
TLS handshake; the HTTP listener exists in tests only. `LokiResponseDecoderTest` checks
JSON fixtures without a network. Covered: URL/parameters/headers, time precision,
stream/metric/metadata DTOs, empty and malformed responses, auth/tenant isolation, errors,
redirect refusal, byte budgets with Content-Length and chunked, Unicode, header/body/TLS
timeouts, cancellation and the absence of secrets in exceptions. Compatibility with Loki
2.6.1 and 3.6.0 is checked by the separate `integrationTest` task (see
[queries.md](queries.md)), including `/series` and [discoverLogs](discovery.md); the live
check is `scripts/live_smoke/run_smoke.py`.
