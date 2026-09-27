# Label discovery and line rendering

## discoverLogs(connection, start, end, label)

Call without label to list the label names Loki has in the window. Call with label="app" to list the values of that label. The window defaults to the last hour. A missing label in a quiet window is not evidence that it never exists; widen start/end. The model uses these values to write a LogQL selector for queryLogs or countLogs.

The tool uses Loki's labels or label/<name>/values endpoint only. It does not query log lines, infer JSON fields or choose a selector for the model. Names and values are sorted. At most 100 label names or 200 values are shown; the response byte budget can shorten the list further and reports how many are hidden.

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

## Line rendering

queryLogs uses EventNormalizer to read level, service, logger, message, trace id and stack trace from query result labels, Loki structured metadata and the JSON line. Generic ECS, logstash and Serilog field names are recognised. Labels take precedence over line fields. serviceLabels in the connection profile controls which labels name a service for display.

A connection's optional formatFile can define regular expressions for plain lines. The first matching format extracts named groups such as message, level, logger, service, thread and pid. Without a matching format, a plain line remains its own message. An optional framePattern folds adjacent standalone frame lines in compact queryLogs output; raw previews and exports retain them. These formats affect local rendering only: they never change the LogQL query or interpret the cause of an event. [connections.md](connections.md) specifies the file.

The default queryLogs view shows HH:mm:ss.SSS LEVEL service  message and shortens long messages and stack traces. raw=true is a bounded preview of the line returned by Loki. exportLogs is the path to complete lines.
