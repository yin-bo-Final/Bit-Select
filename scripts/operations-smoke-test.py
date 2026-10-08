"""Read-only operations API acceptance against real local services; no model calls."""

import argparse
import http.cookiejar
import json
import os
import time
import uuid
from urllib.error import HTTPError, URLError
from urllib.request import HTTPCookieProcessor, ProxyHandler, Request, build_opener


class Client:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.http = build_opener(ProxyHandler({}), HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(self, path, body=None, expected=200, headers=None):
        data = None if body is None else json.dumps(body).encode()
        request = Request(self.base + "/api" + path, data=data, headers={
            "Content-Type": "application/json", "Origin": "http://localhost:3000", **(headers or {}),
        })
        try:
            with self.http.open(request, timeout=20) as response:
                status, raw = response.status, response.read()
                if status == 200 and "/admin/ops/" in path:
                    assert "no-store" in response.headers.get("Cache-Control", ""), "Private monitoring data must not be cached"
        except HTTPError as error:
            status, raw = error.code, error.read()
        assert status == expected, f"{path}: expected HTTP {expected}, got {status}"
        return json.loads(raw or b"{}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--wait-seconds", type=int, default=90)
    args = parser.parse_args()
    anonymous, member, admin = (Client(args.base_url) for _ in range(3))
    deadline = time.monotonic() + args.wait_seconds
    while True:
        try:
            anonymous.call("/admin/ops/overview", expected=401)
            anonymous.call("/ai/admin/ops/summary", expected=401)
            break
        except (AssertionError, OSError, URLError):
            if time.monotonic() >= deadline:
                raise
            time.sleep(0.5)
    paths = ["/admin/ops/overview", "/admin/ops/events?kind=commerce",
             "/admin/ops/events?kind=memory", "/admin/ops/events/memory/" + str(uuid.uuid4()), "/ai/admin/ops/summary",
             "/ai/admin/ops/requests", "/ai/admin/ops/requests/" + str(uuid.uuid4())]
    for path in paths:
        anonymous.call(path, expected=401, headers={"X-User-Role": "ADMIN", "X-User-Id": "1"})
    member.call("/auth/register", {"username": "ops_qa_" + uuid.uuid4().hex[:12],
                "password": "LocalOps-" + uuid.uuid4().hex, "nickname": "运维权限验收"})
    for path in paths:
        member.call(path, expected=403, headers={"X-User-Role": "ADMIN", "X-User-Id": "1"})
    admin.call("/auth/login", {"username": os.environ.get("ADMIN_USERNAME", "admin"),
                              "password": os.environ["ADMIN_PASSWORD"]})
    overview = admin.call("/admin/ops/overview")
    assert {"gateway", "commerce", "catalog", "ai"}.issubset(
        {service["id"] for service in overview["services"]}), "Missing application services"
    assert all(service["status"] in {"up", "down", "unknown"} for service in overview["services"])
    commerce = overview["commerce"]
    assert sum(commerce["ordersByStatus"].values()) == commerce["orders"], "Order states do not reconcile"
    for queue in overview["queues"]:
        assert sum(queue[state] for state in ("pending", "processing", "retrying", "completed", "failed")) == queue["total"]
        assert queue["scope"], "Queue observation scope must be explicit"
    for kind in ("commerce", "memory"):
        rows = admin.call("/admin/ops/events?kind=" + kind + "&page=1&pageSize=3")
        assert len(rows["items"]) <= 3 and rows["page"] == 1 and rows["pageSize"] == 3
        assert all(row["kind"] == kind and "payload" not in row and "content" not in row for row in rows["items"])
        for row in rows["items"]:
            detail = admin.call(f"/admin/ops/events/{kind}/{row['id']}")
            assert detail["id"] == row["id"] and detail["kind"] == kind
        admin.call(f"/admin/ops/events/{kind}/{uuid.uuid4()}", expected=404)
        for state in ("pending", "processing", "retrying", "completed", "failed"):
            filtered = admin.call(f"/admin/ops/events?kind={kind}&status={state}&pageSize=3")
            assert all(row["status"] == state for row in filtered["items"]), "Status filter leaked other rows"
    for query in ("kind=invalid", "kind=memory&status=invalid", "kind=commerce&page=0", "kind=memory&pageSize=51"):
        admin.call("/admin/ops/events?" + query, expected=400)
    summary = admin.call("/ai/admin/ops/summary")
    assert sum(summary[state] for state in ("running", "completed", "failed", "cancelled")) == summary["total"]
    traces = admin.call("/ai/admin/ops/requests?page=1&pageSize=3")
    assert len(traces["items"]) <= 3 and traces["total"] == summary["total"]
    for row in traces["items"]:
        detail = admin.call("/ai/admin/ops/requests/" + row["id"])
        assert detail["id"] == row["id"] and isinstance(detail["steps"], list)
        assert "prompt" not in detail and "answer" not in detail
    for query in ("page=0", "pageSize=51", "status=invalid"):
        admin.call("/ai/admin/ops/requests?" + query, expected=400)
    print("PASS: 运维管理员边界、伪造身份拒绝、真实汇总、分页筛选与安全元数据；无模型调用")


if __name__ == "__main__":
    main()
