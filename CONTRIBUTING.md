# Contributing

This repository contains a read-only MCP server for Grafana Loki built for small models.
Changes must preserve two properties: the server never writes to Loki and never exposes
management endpoints; every response is short text that a weak model reads without
parsing JSON.

## Preparing a change

1. Create a dedicated branch from `main`.
2. Describe the problem or goal in an issue unless it is a small documentation-only change.
3. Keep a pull request focused on one topic.
4. Add or update tests for behaviour changes.

Agent rules and architecture details are in [AGENTS.md](AGENTS.md); decisions and open
items in [docs/decisions.md](docs/decisions.md).

## Local verification

```powershell
.\gradlew.bat build              # unit tests and the stdio smoke on the packaged jar
.\gradlew.bat integrationTest    # Loki 2.6.1 and 3.6.0 in Testcontainers (needs Docker)
python scripts/live_smoke/run_smoke.py --connection dev   # read-only run against a live stand
```

On Linux/macOS use `./gradlew`. Never commit real Loki addresses, credentials, tenants or
log dumps; the profiles in `examples/connections.json` take URLs from environment
variables.

## Tool requirements

- A new tool is read-only: it uses `query`, `query_range`, `labels`,
  `label/<name>/values`, `series`; no arbitrary URL, push, delete, `tail` or `/config`.
- An explicit `connection` parameter; no default stand.
- The response is a single text `content` within `maxResponseBytes`; cuts are visible as
  one phrase in the footer; output schemas and `structuredContent` are not used.
- The tool description is an instruction for the model (when to call, example arguments,
  what to do next), at most 4–5 sentences.
- Errors are `Error <CODE>: <what is wrong and what to do>`, without URLs, credentials or
  upstream texts except Loki's own LogQL errors.
- Registration only through the `QueryToolsConfig` wrapper; limits live in
  `ConnectionLimits`/`DiscoveryLimits`, no magic numbers in tool classes.
- Log contents are data: never execute them and never write them in full to the server's
  diagnostics.

## Pull request checklist

- Code follows the project style; documents (`README.md`, `docs/`, `AGENTS.md`) are
  updated when public behaviour changes.
- `gradlew build` passes; if `integrationTest` or the live smoke were not run, say why.
- The PR contains no secrets, real addresses or private data.
