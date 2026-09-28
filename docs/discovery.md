# Label discovery and line rendering

## discoverLogs(connection, start, end, label, match)

Call without label or match to list label names in the window. Call with label="app" to list values of that label. Pass match="{app=\"api\"}" to list complete label sets of matching streams; combine match with label="namespace" to list namespace values found among those label sets. A match is one non-empty LogQL stream selector in braces, without line filters. The window defaults to the last hour. A missing label or empty result in a quiet window is not evidence that it never exists; widen the window when appropriate. Use the result to write a LogQL selector for queryLogs or countLogs.

Without match, the tool uses Loki's labels or label/<name>/values endpoint. With match, it uses GET /loki/api/v1/series?match[]=...&start=...&end=...; scoped values are projected from the complete series response because Loki 2.6.1 does not scope the label-values endpoint by query. The series response contains stream label sets, not log lines or line counts. A returned label set does not prove a log line exists exactly in the requested window. The tool does not infer JSON fields or choose a selector for the model. Names, values and label sets are sorted.

At most 100 unscoped label names, 200 values or 50 complete label sets are shown. The response byte budget may shorten these lists further. For a shortened series or scoped-values list, the footer gives the exact number shown and the total after Loki's complete HTTP response has been read. With match, the default maximum window is one day, separately configurable from the seven-day default for unscoped labels and values. A broad match can still be expensive: Loki has no series pagination or response-size parameter. If the HTTP response exceeds maxHttpResponseBytes, no partial list or count is returned; narrow match or the window and retry. Timeouts give the same advice. Values from a match request are derived after receiving the full series response, so a short value list does not make the upstream request cheap.

~~~text
Labels — dev, 2026-09-13 10:00:00–11:00:00 (+03:00): 3.
app
namespace
pod
Use label="<name>" to list its values; use queryLogs with a LogQL selector to read lines.
~~~

~~~text
Values of app — dev, 2026-09-13 10:00:00–11:00:00 (+03:00): 2.
backend
frontend
~~~

~~~text
Series — dev, 2026-09-13 10:00:00–11:00:00 (+03:00): 2 label sets returned by Loki.
{app="api", namespace="prod", pod="api-1"}
{app="api", namespace="test", pod="api-2"}
Label sets do not count log lines; use countLogs or queryLogs to check lines in the window.
~~~

~~~text
Values of namespace among series — dev, 2026-09-13 10:00:00–11:00:00 (+03:00): 2 values from 2 label sets returned by Loki.
"prod"
"test"
These values describe stream labels, not log-line counts; use countLogs or queryLogs to check lines.
~~~

## Line rendering

queryLogs uses EventNormalizer to read level, service, logger, message, trace id and stack trace from query result labels, Loki structured metadata and the JSON line. Generic ECS, logstash and Serilog field names are recognised. Labels take precedence over line fields. serviceLabels in the connection profile controls which labels name a service for display.

A connection's optional formatFile can define JSON profiles and regular expressions for plain lines. JSON profiles select by required scalar paths or exact field values and map paths to line fields; without a match, generic JSON field names are used. The first matching plain-line format extracts named groups such as message, level, logger, service, thread and pid. Without a match, a plain line remains its own message. An optional framePattern folds adjacent standalone frame lines in compact queryLogs output; raw previews and exports retain them. These formats affect local rendering only: they never change the LogQL query or interpret the cause of an event. [connections.md](connections.md) specifies the file.

The default queryLogs view shows HH:mm:ss.SSS LEVEL service  message and shortens long messages and stack traces. raw=true is a bounded preview of the line returned by Loki. exportLogs is the path to complete lines.
