"""Read-only live smoke of the packaged jar over stdio, the way an MCP client uses it.

Profiles come from examples/connections.json; the Loki URL of a profile is an ${ENV} placeholder
that the server resolves itself, so no URL or credential ever reaches this script's output.

    python scripts/live_smoke/run_smoke.py --connection dev
    python scripts/live_smoke/run_smoke.py --connection dev --connection tst --window now-24h --verbose

The checks are neutral: the selector for the log steps is taken from the "Next:" line of discoverLogs,
so nothing about a particular stand is hard-coded. Nothing is written to Loki.
"""
from __future__ import annotations

import argparse
import json
import os
import queue
import re
import subprocess
import sys
import tempfile
import threading
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
TOOLS = {"listConnections", "discoverLogs", "countLogs", "queryLogs", "summarizeLogs", "followKey", "getLogContext", "queryMetrics"}
PLACEHOLDER = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)}")


class McpClient:
    """Newline-delimited JSON-RPC over the jar's stdin/stdout; stderr goes to a file."""

    def __init__(self, jar: Path, env: dict[str, str], stderr: Path, verbose: bool) -> None:
        self.process = subprocess.Popen(["java", "-jar", str(jar)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=open(stderr, "ab"), env=env)
        self.lines: "queue.Queue[bytes]" = queue.Queue()
        self.next_id = 1
        self.verbose = verbose
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self) -> None:
        assert self.process.stdout is not None
        for line in self.process.stdout:
            self.lines.put(line)

    def send(self, message: dict) -> None:
        assert self.process.stdin is not None
        self.process.stdin.write((json.dumps(message, ensure_ascii=False) + "\n").encode("utf-8"))
        self.process.stdin.flush()

    def request(self, method: str, params: dict | None = None, timeout: float = 120) -> dict:
        request_id = self.next_id
        self.next_id += 1
        self.send({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params or {}})
        while True:
            try:
                raw = self.lines.get(timeout=timeout)
            except queue.Empty as error:
                raise RuntimeError(f"no response to {method} within {timeout}s (server exited: {self.process.poll()})") from error
            message = json.loads(raw.decode("utf-8"))
            if message.get("id") == request_id:
                return message

    def call(self, name: str, arguments: dict) -> tuple[str, bool]:
        response = self.request("tools/call", {"name": name, "arguments": arguments})
        if "error" in response:
            return json.dumps(response["error"], ensure_ascii=False), True
        result = response["result"]
        text = "\n".join(part.get("text", "") for part in result.get("content", []))
        if self.verbose:
            print(f"--- {name} {json.dumps(arguments, ensure_ascii=False)}\n{text}\n--- {len(text.encode('utf-8'))} bytes")
        return text, bool(result.get("isError"))

    def close(self) -> None:
        if self.process.stdin:
            self.process.stdin.close()
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.process.kill()


class Smoke:
    def __init__(self, client: McpClient, connection: str, window: str, secrets: list[str]) -> None:
        self.client = client
        self.connection = connection
        self.window = window
        self.secrets = secrets
        self.results: list[tuple[str, str, str]] = []

    def check(self, name: str, ok: bool, detail: str = "") -> bool:
        self.results.append(("PASS" if ok else "FAIL", name, detail))
        return ok

    def skip(self, name: str, detail: str) -> None:
        self.results.append(("SKIP", name, detail))

    def call(self, name: str, **arguments) -> tuple[str, bool]:
        text, is_error = self.client.call(name, {"connection": self.connection, **arguments})
        for secret in self.secrets:
            if secret and secret in text:
                self.check(f"{name}: no configured URL in the response", False, "leaked")
        return text, is_error

    def run(self) -> None:
        text, err = self.client.call("listConnections", {})
        self.check("listConnections names the connection", not err and any(line.startswith(self.connection + " ") or line == self.connection
                                                                            for line in text.splitlines()), first_line(text))
        overview, err = self.call("discoverLogs", start=self.window)
        self.check("discoverLogs overview", not err and overview.startswith("Labels in "), first_line(overview))
        match = re.search(r"selector like (\{[^\n]*?})", overview)
        if not match or "<label>" in match.group(1):
            self.skip("log steps", "discoverLogs suggested no concrete selector; pass a busier window with --window")
            return
        selector = match.group(1)
        scoped, err = self.call("discoverLogs", selector=selector, start=self.window)
        self.check("discoverLogs with selector", not err and scoped.startswith("Streams matching "), first_line(scoped))
        label = re.search(r"\n  ([A-Za-z_][A-Za-z0-9_]*): ", overview)
        if label:
            values, err = self.call("discoverLogs", label=label.group(1), start=self.window)
            self.check("discoverLogs label values", not err and values.startswith("Values of "), first_line(values))
        count, err = self.call("countLogs", query=selector, start=self.window)
        self.check("countLogs total", not err and re.match(r"\d+ lines match ", count) is not None, first_line(count))
        buckets, err = self.call("countLogs", query=selector, start=self.window, groupBy="time")
        self.check("countLogs by time", not err and ("By time (" in buckets or buckets.startswith("0 lines match")), first_line(buckets))
        page, err = self.call("queryLogs", query=selector, start=self.window, limit=5)
        self.check("queryLogs page", not err and (" lines:" in page.splitlines()[0] or " of more:" in page.splitlines()[0]
                                                 or "no matching lines." in page), first_line(page))
        summary, err = self.call("summarizeLogs", query=selector, start=self.window, sample=200)
        self.check("summarizeLogs", not err and summary.startswith("Summary of ") and ("Counts are for the" in summary or "no matching lines" in summary),
                   first_line(summary))
        line = next((l for l in page.splitlines()[1:] if re.match(r"\d\d:\d\d:\d\d\.\d{3} ", l)), None)
        if line is None:
            self.skip("getLogContext / raw", "no line in the page to anchor on")
            return
        moment = line[:12]
        context, err = self.call("getLogContext", selector=selector, time=moment, before=3, after=3)
        self.check("getLogContext around a printed line", not err and context.startswith("Context in ") and ">>>" in context, first_line(context))
        raw, err = self.call("queryLogs", query=selector, start=self.window, limit=2, raw=True)
        self.check("queryLogs raw with stream labels", not err and re.search(r"\n\d\d:\d\d:\d\d\.\d{3} \{", raw) is not None, first_line(raw))
        pipeline, err = self.call("getLogContext", selector=selector + ' |= "x"', time=moment)
        self.check("getLogContext rejects a pipeline", err and "stream selector only" in pipeline, first_line(pipeline))
        broken, err = self.call("queryLogs", query=selector + " |= ", start=self.window)
        self.check("Loki parse error is passed on as text", err and "Loki rejected the query" in broken, first_line(broken))
        metric, err = self.call("queryMetrics", query=f"sum(count_over_time({selector}[5m]))", start=self.window)
        self.check("queryMetrics", not err and (", step " in metric), first_line(metric))


def first_line(text: str) -> str:
    return text.splitlines()[0][:160] if text else ""


def load_profiles(path: Path) -> dict:
    with open(path, encoding="utf-8") as file:
        return json.load(file)["connections"]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--connection", action="append", help="profile name from the connections file; repeatable. Default: every profile whose ${ENV} URL is set")
    parser.add_argument("--connections-file", default=str(REPO_ROOT / "examples" / "connections.json"))
    parser.add_argument("--jar", default=str(REPO_ROOT / "build" / "libs" / "loki-mcp-server.jar"))
    parser.add_argument("--window", default="now-6h", help='start of the window, e.g. "now-24h" (default now-6h)')
    parser.add_argument("--verbose", action="store_true", help="print every tool response in full")
    args = parser.parse_args()

    jar = Path(args.jar)
    if not jar.exists():
        print(f"jar not found: {jar}; run gradlew bootJar first", file=sys.stderr)
        return 2
    profiles = load_profiles(Path(args.connections_file))
    chosen: dict[str, dict] = {}
    for name, profile in profiles.items():
        variables = PLACEHOLDER.findall(json.dumps(profile))
        if args.connection and name not in args.connection:
            continue
        missing = [v for v in variables if v not in os.environ]
        if missing:
            if args.connection:
                print(f"{name}: environment variable(s) not set: {', '.join(missing)}", file=sys.stderr)
                return 2
            continue
        if not args.connection and not variables:
            continue  # a literal URL such as the local example is not a live stand
        rules = profile.get("rulesFile")
        if rules and not Path(rules).is_absolute():
            # The temporary connections.json lives elsewhere; a relative rulesFile is resolved against the original file.
            profile = dict(profile, rulesFile=str(Path(args.connections_file).resolve().parent / rules))
        chosen[name] = profile
    if not chosen:
        print("no profile selected: set LOKI_DEV_URL / LOKI_TST_URL (see examples/connections.json) or pass --connection", file=sys.stderr)
        return 2
    secrets = sorted({os.environ[v] for p in chosen.values() for v in PLACEHOLDER.findall(json.dumps(p)) if os.environ.get(v)}, key=len, reverse=True)

    with tempfile.TemporaryDirectory(prefix="loki-mcp-smoke-") as directory:
        data = Path(directory)
        (data / "connections.json").write_text(json.dumps({"connections": chosen}, ensure_ascii=False, indent=1), encoding="utf-8")
        env = dict(os.environ, LOKI_MCP_CONNECTIONS_FILE=str(data / "connections.json"), LOKI_MCP_DATA_DIR=str(data))
        client = McpClient(jar, env, data / "stderr.log", args.verbose)
        failures = 0
        try:
            init = client.request("initialize", {"protocolVersion": "2024-11-05", "capabilities": {}, "clientInfo": {"name": "live-smoke", "version": "1"}})
            client.send({"jsonrpc": "2.0", "method": "notifications/initialized"})
            info = init.get("result", {})
            print(f"server: {info.get('serverInfo', {}).get('name')} {info.get('serverInfo', {}).get('version')}, "
                  f"instructions: {'yes' if info.get('instructions') else 'MISSING'}")
            tools = {tool["name"] for tool in client.request("tools/list")["result"]["tools"]}
            print(f"{'PASS' if tools == TOOLS else 'FAIL'} tools/list {sorted(tools)}")
            failures += tools != TOOLS
            for name in chosen:
                print(f"== {name} (window {args.window})")
                smoke = Smoke(client, name, args.window, secrets)
                smoke.run()
                for status, check, detail in smoke.results:
                    print(f"{status} {check}" + (f": {detail}" if detail else ""))
                    failures += status == "FAIL"
        finally:
            client.close()
            log = (data / "stderr.log").read_text(encoding="utf-8", errors="replace")
            for secret in secrets:
                if secret in log:
                    print("FAIL configured URL found in server stderr", file=sys.stderr)
                    failures += 1
    print(f"{'OK' if failures == 0 else f'{failures} failure(s)'}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
