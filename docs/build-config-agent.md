# Build, configuration and verification

Read this before building, running tests, changing dependencies, changing a configuration knob or updating verification instructions.

## Stack and build

The project targets Java 21, Gradle 9.3.1, Spring Boot 4.2.0-M2 and Spring AI 2.1.0-M1. Verify actual versions in `gradle/libs.versions.toml`; change dependencies through the version catalog. The Java package root is `ru.it_spectrum.ai.loki.mcp`. `bootJar` produces `build/libs/loki-mcp-server.jar`. Use the checked-in wrapper, never Maven or an arbitrary system Gradle. Do not bring Java 25 from the Redmine donor without a separate reason.

```powershell
.\gradlew.bat classes
.\gradlew.bat test
.\gradlew.bat test --tests '<fully qualified test class>'
.\gradlew.bat bootJar
.\gradlew.bat build
.\gradlew.bat integrationTest --console=plain
java -jar build/libs/loki-mcp-server.jar
```

On Linux/macOS use `./gradlew`. Check that a suitable JDK and Docker are installed before assuming they are available. This development machine has previously supplied Java 21 from `C:\Program Files\BellSoft\LibericaJDK-21`; the project does not hard-code that path.

The jar waits for JSON-RPC on stdin. Keep stderr separate from stdout when an MCP client is attached. Logs go to stderr and `~/.loki-mcp-server/logs/loki-mcp-server.log`; `LOKI_MCP_DATA_DIR` changes the data directory. `LOKI_MCP_CONNECTIONS_FILE` changes the external connection file. The only `application.yml` defaults are local server settings and the MCP instructions; connection credentials and addresses remain external. See [connections-agent.md](connections-agent.md).

## Verification by risk

For documentation-only changes, check links, the instruction index, consistency with code and Git, and the diff. Do not create tests that merely restate prose. For code changes, run focused tests related to the change. Public tool-contract changes also require text-output and stdio interaction checks. Once a check succeeds, do not repeat it without new changes or a concrete remaining risk.

The regular `test` task depends on `bootJar` and uses mocks or loopback HTTP only. `StdioSmokeTest` starts the jar as a separate process and checks initialization, instructions, concurrent pings and calls, all five text tools, errors, response budgets, one export, refusal of an invalid file, default and override configuration paths, clean stdout and absence of secrets. Client transport tests are `LokiHttpClientTest` and `LokiResponseDecoderTest` (`--tests 'ru.it_spectrum.ai.loki.mcp.client.*'`). Query, export and budget checks should cover identical timestamps, several streams, real duplicates, large stack traces, Unicode and the minimum response budget.

`integrationTest` is opt-in, requires Docker and pinned `grafana/loki:2.6.1` and `grafana/loki:3.6.0`, and ingests only into its own containers. A missing Docker is a failing task, not a skip; report it as an unavailable check rather than a pass. Live smoke is read-only against an explicitly configured Loki, outside build/test, with short windows and small limits:

```powershell
.\gradlew.bat bootJar
$env:LOKI_DEV_URL = "<url>"
python scripts/live_smoke/run_smoke.py --connection dev [--window now-24h] [--verbose]
```

The live script uses profiles from `examples/connections.json` and verifies that the stand URL appears neither in responses nor stderr. It needs Python 3.10+ and the standard library only. Record missing credentials or endpoints as unavailable checks. Findings from real model use belong in [decisions.md](decisions.md#open-items).
