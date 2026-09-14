# Security policy

## Supported versions

The current state of the `main` branch and the latest release are supported.

## Reporting a vulnerability

Do not publish vulnerability details in an open issue.

If GitHub private vulnerability reporting is enabled for this repository, use it.
Otherwise open an issue without technical details and ask the maintainer for a private
channel.

Include:

- the affected version or commit;
- a brief risk summary;
- minimal reproduction steps, if they can be shared safely;
- the expected impact;
- a known workaround, if any.

## What matters for this project

The server reads real Loki instances with credentials from `connections.json` and hands
log contents to a model. Reports about the following are especially important:

- bypassing the read-only contract (any path to push, delete, `tail`, `/config` or an
  arbitrary URL);
- leaking `url`, credentials, `tenant` or Loki response bodies into tool responses, error
  messages, stderr or the log file;
- escaping `maxResponseBytes`, `maxHttpResponseBytes`, `maxIntervalSeconds`,
  `maxEntries` — a way to exhaust memory or make a stand execute heavy queries;
- executing log contents as instructions (the server treats lines as data and repeats
  this in `instructions`).

Real stand addresses and credentials must never appear in the repository or in issues.
