"""Verify durable Nacos Sentinel configuration against the running local gateway.
Temporarily limits product browsing to one request/minute; always restores the
original configuration. --restart also verifies gateway restart persistence.
"""
import argparse
import json
import subprocess
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, ProxyHandler, build_opener

ROOT = Path(__file__).resolve().parent.parent
HTTP = build_opener(ProxyHandler({}))
NACOS = "http://127.0.0.1:18848/nacos/v1/cs/configs"
PARAMS = {"dataId": "bit-gateway-flow-rules.json", "group": "BIT_SELECT"}

def publish(content):
    body = urlencode({**PARAMS, "content": content, "type": "json"}).encode()
    with HTTP.open(Request(NACOS, data=body, method="POST"), timeout=10) as response:
        assert response.read() == b"true", "Nacos did not acknowledge persisted configuration"

def assert_limited():
    for _ in range(12):
        try:
            with HTTP.open("http://127.0.0.1:8080/api/products?pageSize=1", timeout=5) as response:
                assert response.status == 200
        except HTTPError as error:
            body = json.loads(error.read())
            assert error.code == 429, f"Expected 429, got {error.code}"
            assert body.get("code") == "RATE_LIMITED", "Response did not come from Sentinel"
            return
        time.sleep(0.2)
    raise AssertionError("Persisted Sentinel change was not applied")

def restart_gateway():
    command = r"""
$ErrorActionPreference='Stop'
$entries=@(Get-Content .local/application-processes.json -Raw | ConvertFrom-Json)
$entry=$entries | Where-Object service -EQ 'gateway' | Select-Object -Last 1
$process=Get-Process -Id $entry.pid -ErrorAction Stop
$detail=Get-CimInstance Win32_Process | Where-Object ProcessId -EQ $entry.pid
if(-not $detail -or $process.StartTime.ToUniversalTime().Ticks -ne ([datetime]$entry.startedUtc).ToUniversalTime().Ticks -or -not $detail.CommandLine.Contains($entry.marker)){throw 'Gateway process identity did not match'}
Stop-Process -Id $entry.pid
Wait-Process -Id $entry.pid -Timeout 10 -ErrorAction SilentlyContinue
./scripts/Start-Application.ps1 -SkipAi
"""
    subprocess.run(["pwsh", "-NoProfile", "-Command", command], cwd=ROOT, check=True, timeout=40)
    deadline = time.monotonic() + 35
    while time.monotonic() < deadline:
        try:
            with HTTP.open("http://127.0.0.1:8080/actuator/health", timeout=2) as response:
                if response.status == 200:
                    return
        except (URLError, TimeoutError):
            pass
        time.sleep(0.3)
    raise AssertionError("Gateway did not become ready after restart")

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--restart", action="store_true")
    args=parser.parse_args()
    with HTTP.open(NACOS + "?" + urlencode(PARAMS), timeout=10) as response:
        original=response.read().decode()
    try:
        rules=json.loads(original)
        for rule in rules:
            if rule["resource"] == "catalog":
                rule.update(count=1, intervalSec=60)
        publish(json.dumps(rules))
        assert_limited()
        print("PASS: Nacos persisted rule hot-reloads and returns structured HTTP 429")
        if args.restart:
            restart_gateway()
            assert_limited()
            print("PASS: persisted limits survive a gateway restart")
    finally:
        publish(original)
    time.sleep(1.5)
    with HTTP.open("http://127.0.0.1:8080/api/products?pageSize=1", timeout=5) as response:
        assert response.status == 200
    print("PASS: original production rules restored; product browsing returns HTTP 200")

if __name__ == "__main__":
    main()
