"""Exercise real AI SSE, optionally through the real Gateway and Next proxy.

Requires running MySQL, Redis, Nacos and Commerce; uses a fresh QA account whose
memory is disabled. Only the subprocesses started here are stopped. Existing
IDEA services and private environment files are never changed. The model is a
loopback HTTP fixture: no SiliconFlow account, key or paid call is used.

Example after Maven verify and npm ci:
  python scripts/sse-integration-test.py --env-file infra/.env --with-next

CI may provide --jar-dir .local/backend-jars from the build job, and
--env-file infra/.env.example. Logs, fixtures and timings remain in .local/.
The minimal Next fixture copies the actual next.config.ts byte for byte and
uses the installed, lockfile-pinned Next runtime. It tests HTTP forwarding,
not the assistant React UI; UI/parser tests are separate.
"""

import argparse
import contextlib
import http.cookiejar
import json
import os
from pathlib import Path
import re
import select
import shutil
import socket
import subprocess
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import HTTPCookieProcessor, ProxyHandler, Request, build_opener


ROOT = Path(__file__).resolve().parent.parent
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def loopback_url(value):
    parsed = urlparse(value)
    check(parsed.scheme == "http" and parsed.hostname in {"localhost", "127.0.0.1", "::1"}
          and not parsed.username and not parsed.password, "Only credential-free loopback HTTP URLs are allowed")
    return value.rstrip("/")


def available_port(port):
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", port))


def load_environment(path):
    env = os.environ.copy()
    if path:
        for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
            if not line.strip() or line.lstrip().startswith("#") or "=" not in line:
                continue
            name, value = line.split("=", 1)
            env[name.strip()] = value.strip().strip("\"'")
    # Never inherit a paid-model key/base URL, JVM injected arguments or a proxy.
    for name in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy",
                 "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        env.pop(name, None)
    env.update(NO_PROXY="*", no_proxy="*", SILICONFLOW_API_KEY="synthetic-sse-test-only",
               AI_MEMORY_ENABLED="false", NEXT_TELEMETRY_DISABLED="1")
    return env


class ModelFixture(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self):
        super().__init__(("127.0.0.1", 0), ModelHandler)
        self.records = {}
        self.unexpected_paths = []

    def scenario(self, mode):
        marker = "SSECASE_" + uuid.uuid4().hex + "_" + mode
        self.records[marker] = {"mode": mode}
        return marker, self.records[marker]


class ModelHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass

    def reply_json(self, payload):
        data = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(data)
        self.wfile.flush()

    def wait_connected(self, seconds, record):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            readable, _, _ = select.select([self.connection], [], [], 0.05)
            if readable and self.connection.recv(1, socket.MSG_PEEK) == b"":
                record["upstreamDisconnectedAt"] = time.monotonic()
                return False
        return True

    def sse(self, content=None, finish=None, done=False):
        payload = "[DONE]" if done else json.dumps({"choices": [{
            "delta": {} if content is None else {"content": content}, "finish_reason": finish,
        }]}, ensure_ascii=False)
        self.wfile.write(("data: " + payload + "\n\n").encode())
        self.wfile.flush()

    def do_POST(self):
        self.close_connection = True
        if self.path != "/v1/chat/completions":
            self.server.unexpected_paths.append(self.path)
            self.send_error(400, "Unexpected model endpoint in general-intent test")
            return
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        text = "\n".join(str(message.get("content", "")) for message in body.get("messages", []))
        markers = re.findall(r"SSECASE_[a-f0-9]+_[a-z]+", text)
        if not markers or markers[-1] not in self.server.records:
            self.server.unexpected_paths.append("unrecognized-request")
            self.send_error(400, "Missing synthetic test marker")
            return
        marker = markers[-1]
        record = self.server.records[marker]
        mode = record["mode"]
        try:
            if not body.get("stream"):
                if body.get("max_tokens") == 200:
                    answer = '{"intent":"general"}'
                else:
                    answer = "你好，请回应这个流式测试：" + marker
                    record["rewriteStartedAt"] = time.monotonic()
                    if mode == "cancelrewrite":
                        self.send_response(200)
                        self.send_header("Content-Type", "application/json")
                        self.send_header("Content-Length", "4096")
                        self.end_headers()
                        self.wfile.flush()
                        self.wait_connected(30, record)
                        return
                self.reply_json({"choices": [{"message": {"content": answer}, "finish_reason": "stop"}]})
                return
            record["streamStartedAt"] = time.monotonic()
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.flush()
            if mode == "timeout":
                self.wait_connected(30, record)
                return
            if mode == "normal" and not self.wait_connected(2.2, record):
                return
            record["firstDeltaSentAt"] = time.monotonic()
            self.sse("收到，")
            if mode == "cancel":
                self.wait_connected(30, record)
                return
            if not self.wait_connected(1.5 if mode == "normal" else 0.15, record):
                return
            if mode == "cut":
                return  # Real TCP EOF, deliberately without finish_reason or [DONE].
            self.sse("这是本地的流式验证。", finish="length" if mode == "length" else "stop")
            self.sse(done=True)
            record["terminalSentAt"] = time.monotonic()
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
            record["upstreamDisconnectedAt"] = time.monotonic()
        finally:
            record["lastHandlerFinishedAt"] = time.monotonic()


class Client:
    def __init__(self, base):
        self.base = loopback_url(base)
        self.http = build_opener(ProxyHandler({}), HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def request(self, method, path, body=None):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        return self.http.open(Request(self.base + path, data=data, method=method, headers={
            "Content-Type": "application/json", "Origin": "http://localhost:3000",
        }), timeout=25)

    def call(self, method, path, body=None, status=200):
        try:
            with self.request(method, path, body) as response:
                actual, data = response.status, response.read()
        except HTTPError as error:
            actual, data = error.code, error.read()
        check(actual == status, f"{method} {path}: expected HTTP {status}, got {actual}")
        return json.loads(data or b"{}")


def frames(response):
    kind, data = "message", []
    for raw in response:
        line = raw.decode("utf-8").rstrip("\r\n")
        if line.startswith(":"):
            yield "comment", line[1:].strip(), time.monotonic()
        elif not line:
            if data:
                yield kind, json.loads("\n".join(data)), time.monotonic()
            kind, data = "message", []
        elif line.startswith("event:"):
            kind = line[6:].strip()
        elif line.startswith("data:"):
            data.append(line[5:].lstrip(" "))
    check(not data, "SSE ended in an unterminated frame")


def wait_until(predicate, seconds, failure):
    deadline = time.monotonic() + seconds
    while not predicate():
        check(time.monotonic() < deadline, failure)
        time.sleep(0.1)


def await_ready(client, processes, seconds):
    deadline = time.monotonic() + seconds
    while True:
        for name, process in processes:
            check(process.poll() is None, f"Isolated {name} exited; inspect the saved local log")
        try:
            client.call("GET", "/api/ai/memories", status=401)
            client.call("GET", "/api/auth/me", status=401)
            return
        except (AssertionError, URLError, OSError):
            check(time.monotonic() < deadline, "Isolated proxy/AI/Commerce readiness timed out")
            time.sleep(0.5)


def run_chat(client, model, mode, conversation=None):
    marker, record = model.scenario(mode)
    body = {"message": "你好。" + marker}
    if conversation:
        body["conversationId"] = conversation
    started = time.monotonic()
    events = []
    response = client.request("POST", "/api/ai/chat", body)
    try:
        check(response.status == 200, "Chat did not accept the request")
        check("text/event-stream" in response.headers.get("Content-Type", ""), "Wrong SSE content type")
        cache = response.headers.get("Cache-Control", "").lower()
        check("no-cache" in cache and "no-transform" in cache, "SSE cache/transform protection missing")
        check(response.headers.get("X-Accel-Buffering", "").lower() == "no", "SSE proxy buffering guard missing")
        for kind, value, timestamp in frames(response):
            events.append((kind, value, timestamp))
            if kind == "delta" and "firstDeltaReceivedAt" not in record:
                record["firstDeltaReceivedAt"] = timestamp
                record["terminalAlreadySentOnFirstDelta"] = "terminalSentAt" in record
            abort = mode == "cancel" and kind == "delta"
            abort_rewrite = (mode == "cancelrewrite" and kind == "phase"
                             and value.get("code") == "rewrite" and value.get("status") == "running")
            if abort or abort_rewrite:
                if abort_rewrite:
                    wait_until(lambda: "rewriteStartedAt" in record, 3, "Rewrite never reached local model")
                record["clientAbortedAt"] = time.monotonic()
                break
    finally:
        response.close()
    elapsed = time.monotonic() - started
    metadata = [value for kind, value, _ in events if kind == "meta"]
    check(len(metadata) == 1, "Expected exactly one conversation metadata event")
    cid = metadata[0]["conversationId"]
    check(not conversation or cid == conversation, "Conversation changed during retry")
    if mode in {"cancel", "cancelrewrite"}:
        wait_until(lambda: "upstreamDisconnectedAt" in record, 6,
                   "Browser abort did not close model request before the application deadline")
        # The worker's owner-checked lock cleanup follows cancelling the model socket.
        time.sleep(0.3)
    else:
        terminals = [(kind, value) for kind, value, _ in events if kind in {"done", "error"}]
        check(len(terminals) == 1, "Stream must contain exactly one done/error terminal event")
        check(events[-1][0] in {"done", "error"}, "Application emitted data after its terminal event")
        if mode == "normal":
            check(terminals[0][0] == "done", "Normal stream failed")
            check(terminals[0][1]["conversationId"] == cid, "Done has the wrong conversation ID")
            check(not record.get("terminalAlreadySentOnFirstDelta", True),
                  "Response was buffered: first delta arrived only after upstream finished")
            check(record["terminalSentAt"] - record["firstDeltaReceivedAt"] > 0.8,
                  "Insufficient streaming lead time before upstream completion")
            check(any(kind == "comment" and value == "keep-alive" for kind, value, _ in events),
                  "Missing heartbeat during the model's first-token wait")
            phases = {(value.get("code"), value.get("status")) for kind, value, _ in events if kind == "phase"}
            check(all((phase, state) in phases for phase in ("rewrite", "intent", "retrieve", "compose", "save")
                      for state in ("running", "completed")), "Incomplete workflow phase feedback")
        else:
            check(terminals[0][0] == "error", "Incomplete model stream was falsely marked done")
            expected = "AI_TIMEOUT" if mode == "timeout" else "AI_FAILED"
            check(terminals[0][1].get("code") == expected, f"Expected terminal {expected}")
            check(terminals[0][1].get("retryable") is True, "Error should support an explicit user retry")
            check(terminals[0][1].get("partial") is (mode != "timeout"), "Incorrect partial-answer indication")
            if mode == "timeout":
                check(any(kind == "comment" for kind, _, _ in events), "Timeout wait did not receive heartbeat")
                wait_until(lambda: "upstreamDisconnectedAt" in record, 3, "Deadline did not cancel upstream")
    detail = client.call("GET", "/api/ai/conversations/" + cid)
    messages = detail["messages"]
    if mode == "normal":
        answer = "".join(value["content"] for kind, value, _ in events if kind == "delta")
        check(len(messages) == 2 and messages[0]["role"] == "user" and messages[1]["role"] == "assistant",
              "A completed fresh/retried turn was not saved exactly once")
        check(messages[1]["content"] == answer, "Persisted answer differs from streamed text")
    else:
        check(messages == [], "Failed or cancelled answer was saved as a completed conversation")
    result = {"mode": mode, "seconds": round(elapsed, 3), "events": [kind for kind, _, _ in events]}
    if "firstDeltaReceivedAt" in record:
        result["firstDeltaSeconds"] = round(record["firstDeltaReceivedAt"] - started, 3)
    if "clientAbortedAt" in record:
        result["cancelPropagationSeconds"] = round(record["upstreamDisconnectedAt"] - record["clientAbortedAt"], 3)
    print("PASS: " + json.dumps(result, ensure_ascii=False), flush=True)
    return cid, result


def find_jar(service, supplied, jar_dir=None):
    if supplied:
        path = Path(supplied).resolve()
        check(path.is_file(), f"Missing {service} JAR")
        return path
    base = Path(jar_dir).resolve() if jar_dir else ROOT / "backend"
    jars = [path for path in (base / service / "target").glob("*.jar")
            if not any(part in path.name for part in ("sources", "javadoc", "original"))]
    check(len(jars) == 1, f"Expected one built {service} JAR; use --{('ai' if service == 'ai-service' else service)}-jar")
    return jars[0]


def start_process(stack, processes, name, command, cwd, env, directory):
    log = stack.enter_context((directory / (name + ".log")).open("wb"))
    flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    process = subprocess.Popen(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT,
                               creationflags=flags)
    processes.append((name, process))

    def stop_owned_process():
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=12)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    stack.callback(stop_owned_process)


def prepare_next(directory, env):
    fixture = directory / "next-app"
    fixture.mkdir()
    web = ROOT / "web"
    modules = web / "node_modules"
    check((modules / "next" / "package.json").is_file(), "Run npm ci in web before --with-next")
    for name in ("next.config.ts", "package.json"):
        shutil.copy2(web / name, fixture / name)
    # Absolute dependency paths are passed as environment data, never shell interpolation.
    if os.name == "nt":
        junction_env = dict(env, SSE_NODE_MODULES=str(modules), SSE_FIXTURE_MODULES=str(fixture / "node_modules"))
        subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                        "$ErrorActionPreference='Stop'; $null = New-Item -ItemType Junction "
                        "-Path $env:SSE_FIXTURE_MODULES -Target $env:SSE_NODE_MODULES"],
                       env=junction_env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                       creationflags=subprocess.CREATE_NO_WINDOW)
    else:
        (fixture / "node_modules").symlink_to(modules, target_is_directory=True)
    (fixture / "app").mkdir()
    (fixture / "app" / "layout.js").write_text(
        "export default function Layout({children}){return <html><body>{children}</body></html>}\n", encoding="utf-8")
    (fixture / "app" / "page.js").write_text(
        "export default function Page(){return <p>Local SSE proxy fixture</p>}\n", encoding="utf-8")
    # Use the CLI's own server entry point in one owned Node process. Avoid a CLI
    # supervisor spawning an untracked replacement process or sharing web/.next.
    entry = directory / "next-server.cjs"
    entry.write_text(
        "const {startServer}=require(process.env.SSE_NEXT_SERVER);\n"
        "startServer({dir:process.env.SSE_NEXT_DIR,hostname:'127.0.0.1',"
        "port:Number(process.env.SSE_NEXT_PORT),isDev:true,allowRetry:false})"
        ".catch(()=>{console.error('SSE Next fixture failed');process.exit(1)});\n", encoding="utf-8")
    return fixture, entry, modules / "next" / "dist" / "server" / "lib" / "start-server.js"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--env-file", help="Only read this middleware env file; never load the paid model .env.local")
    parser.add_argument("--java", default="java")
    parser.add_argument("--node", default="node")
    parser.add_argument("--ai-jar")
    parser.add_argument("--gateway-jar")
    parser.add_argument("--jar-dir", help="Build artifacts root containing <service>/target/*.jar")
    parser.add_argument("--ai-port", type=int, default=18083)
    parser.add_argument("--gateway-port", type=int, default=18080)
    parser.add_argument("--next-port", type=int, default=13000)
    parser.add_argument("--with-next", action="store_true")
    parser.add_argument("--commerce-url", default="http://127.0.0.1:8081")
    parser.add_argument("--catalog-url", default="http://127.0.0.1:8082")
    parser.add_argument("--wait-seconds", type=int, default=180)
    args = parser.parse_args()
    commerce, catalog = loopback_url(args.commerce_url), loopback_url(args.catalog_url)
    for port in (args.ai_port, args.gateway_port, *([args.next_port] if args.with_next else [])):
        available_port(port)
    ai_jar = find_jar("ai-service", args.ai_jar, args.jar_dir)
    gateway_jar = find_jar("gateway", args.gateway_jar, args.jar_dir)
    directory = ROOT / ".local" / "sse-integration" / (time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6])
    directory.mkdir(parents=True)
    print("INFO: isolated test logs: " + str(directory), flush=True)
    env = load_environment(args.env_file)
    processes, results = [], []
    with contextlib.ExitStack() as stack:
        model = ModelFixture()
        stack.callback(model.server_close)
        threading.Thread(target=model.serve_forever, daemon=True).start()
        stack.callback(model.shutdown)
        model_url = f"http://127.0.0.1:{model.server_port}/v1"
        env.update(SILICONFLOW_BASE_URL=model_url,
                   CATALOG_SEED_PATH=str(ROOT / "data" / "products.json"),
                   AI_RANKING_MODEL=str(ROOT / "data" / "ranking" / "model.json"),
                   AI_PORT=str(args.ai_port), AI_DUBBO_PORT=str(args.ai_port + 1000))
        copied_ai, copied_gateway = directory / "ai-service.jar", directory / "gateway.jar"
        shutil.copy2(ai_jar, copied_ai)
        shutil.copy2(gateway_jar, copied_gateway)
        java = [args.java, "-Xms64m", "-XX:ActiveProcessorCount=2"]
        start_process(stack, processes, "ai", java + ["-Xmx512m", "-jar", str(copied_ai),
                      "--ai.base-url=" + model_url, "--ai.api-key=synthetic-sse-test-only", "--ai.memory-enabled=false",
                      "--ai.heartbeat-seconds=1", "--ai.request-timeout-seconds=10",
                      "--dubbo.application.name=bit-select-sse-test", "--dubbo.registry.register=false"], ROOT, env, directory)
        gateway_env = dict(env, PORT=str(args.gateway_port), AI_URL=f"http://127.0.0.1:{args.ai_port}",
                           CATALOG_URL=catalog, COMMERCE_URL=commerce)
        start_process(stack, processes, "gateway", java + ["-Xmx256m", "-jar", str(copied_gateway)],
                      ROOT, gateway_env, directory)
        base = f"http://127.0.0.1:{args.gateway_port}"
        await_ready(Client(base), processes, args.wait_seconds)
        if args.with_next:
            fixture, entry, next_server = prepare_next(directory, env)
            next_env = dict(env, API_GATEWAY_URL=base, SSE_NEXT_SERVER=str(next_server),
                            SSE_NEXT_DIR=str(fixture), SSE_NEXT_PORT=str(args.next_port), NODE_ENV="development")
            start_process(stack, processes, "next", [args.node, str(entry)], fixture, next_env, directory)
            base = f"http://127.0.0.1:{args.next_port}"
        client = Client(base)
        if args.with_next:
            await_ready(client, processes, args.wait_seconds)
        nonce = uuid.uuid4().hex[:12]
        client.call("POST", "/api/auth/register", {"username": "sse_qa_" + nonce,
                    "password": "LocalSse-" + uuid.uuid4().hex, "nickname": "本地流式验证"})
        client.call("PUT", "/api/ai/memory-preference", {"enabled": False})
        for mode in ("normal", "cut", "length", "timeout", "cancel", "cancelrewrite"):
            cid, result = run_chat(client, model, mode)
            results.append(result)
            if mode != "normal":
                # One deliberate retry after observing completion/cancellation proves
                # that the conversation lock and permit have been released.
                _, retry = run_chat(client, model, "normal", cid)
                retry["retryAfter"] = mode
                results.append(retry)
        check(not model.unexpected_paths, "Unexpected model/embedding/rerank call; test scope changed")
        report = {"chain": "Next→Gateway→AI→local fake model" if args.with_next else "Gateway→AI→local fake model",
                  "paidModelCalls": 0, "results": results}
        (directory / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("PASS: " + report["chain"] + "; all streaming checks passed; no paid model API calls", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError, URLError, subprocess.SubprocessError) as error:
        print(f"FAIL: {type(error).__name__}: {error}", file=sys.stderr)
        sys.exit(1)
