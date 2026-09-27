"""Real HTTP acceptance checks for the local AI assistant.

Default: keyless authorization, account isolation, and persisted memory settings.
--with-model: additionally consumes the server's configured model API to verify
SSE completion, source persistence, live-budget recommendations and conversation
ownership. --with-worker adds one controlled memory extraction and waits for the
real RocketMQ worker, proving disabled-memory behavior against a positive control.
No model key is read, supplied or printed by this script. QA users/audit history
are retained; tests never touch an existing user's memory or conversations.
"""
import argparse
import http.cookiejar
import json
import re
import sys
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import HTTPCookieProcessor, ProxyHandler, Request, build_opener

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

class Client:
    def __init__(self, base):
        self.base = base.rstrip("/") + "/api"
        self.cookies = http.cookiejar.CookieJar()
        self.http = build_opener(ProxyHandler({}), HTTPCookieProcessor(self.cookies))

    def request(self, method, path, body=None, timeout=30):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = Request(self.base + path, data=data, method=method,
                          headers={"Content-Type": "application/json", "Origin": "http://localhost:3000"})
        return self.http.open(request, timeout=timeout)

    def call(self, method, path, body=None, expected=(200,)):
        try:
            with self.request(method, path, body) as response:
                status, data = response.status, response.read()
        except HTTPError as error:
            status, data = error.code, error.read()
        assert status in expected, f"{method} {path}: HTTP {status}; {data[:240]!r}"
        return json.loads(data or b"{}")

    def chat(self, message, conversation=None):
        body = {"message": message}
        if conversation:
            body["conversationId"] = conversation
        events, event, data = [], "message", []
        with self.request("POST", "/ai/chat", body, timeout=190) as response:
            assert response.status == 200
            assert "text/event-stream" in response.headers.get("Content-Type", "")
            for raw in response:
                line = raw.decode("utf-8").rstrip("\r\n")
                if not line:
                    if data:
                        events.append((event, json.loads("\n".join(data))))
                    event, data = "message", []
                elif line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:"):
                    data.append(line[5:].lstrip())
        if data:
            events.append((event, json.loads("\n".join(data))))
        errors = [payload for kind, payload in events if kind == "error"]
        assert not errors, f"AI returned an error event: {errors}"
        metadata = [payload for kind, payload in events if kind == "meta"]
        completed = [payload for kind, payload in events if kind == "done"]
        assert len(metadata) == len(completed) == 1, "Stream ended without exactly one meta/done pair"
        cid = metadata[0]["conversationId"]
        assert completed[0]["conversationId"] == cid
        answer = "".join(payload["content"] for kind, payload in events if kind == "delta")
        assert answer.strip(), "Completed stream did not contain answer text"
        source_events = [payload for kind, payload in events if kind == "sources"]
        assert len(source_events) == 1, "Expected one evidence event"
        return {"id": cid, "answer": answer, "sources": source_events[0]["items"], "events": events}

def ready(client, wait):
    deadline = time.monotonic() + wait
    while True:
        try:
            client.call("GET", "/ai/memories", expected=(401,))
            return
        except (AssertionError, URLError, TimeoutError):
            if time.monotonic() >= deadline:
                raise
            time.sleep(0.5)

def verify_persisted(client, result, turns=1):
    detail = client.call("GET", f"/ai/conversations/{result['id']}")
    messages = detail["messages"]
    assert len(messages) == 2 * turns and [m["role"] for m in messages] == ["user", "assistant"] * turns
    assert messages[-1]["content"] == result["answer"], "Persisted answer differs from the completed stream"
    assert messages[-1].get("sources") == result["sources"], "Citations were lost or changed after reload"
    assert detail["stats"]["contextCapacity"] > 0
    assert detail["stats"]["tokenizer"] == "Qwen3"
    return detail

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--wait-seconds", type=int, default=90)
    parser.add_argument("--with-model", action="store_true")
    parser.add_argument("--with-worker", action="store_true")
    parser.add_argument("--worker-wait-seconds", type=int, default=100)
    args = parser.parse_args()
    assert not args.with_worker or args.with_model, "--with-worker requires --with-model"
    anonymous, owner, other = (Client(args.base_url) for _ in range(3))
    ready(anonymous, args.wait_seconds)
    for path in ("/ai/conversations", "/ai/memories", "/ai/knowledge"):
        anonymous.call("GET", path, expected=(401,))
    anonymous.call("POST", "/ai/chat", {"message": "测试"}, expected=(401,))
    nonce, password = uuid.uuid4().hex[:10], "AiSmoke-" + uuid.uuid4().hex
    owner_name = "ai_owner_" + nonce
    owner.call("POST", "/auth/register", {"username": owner_name, "password": password, "nickname": "AI验收用户"})
    other.call("POST", "/auth/register", {"username": "ai_other_" + nonce, "password": password, "nickname": "AI隔离验收"})
    owner.call("GET", "/ai/knowledge", expected=(403,))
    owner.call("POST", "/ai/knowledge/reindex", {}, expected=(403,))
    assert owner.call("GET", "/ai/conversations")["items"] == []
    assert other.call("GET", "/ai/conversations")["items"] == []
    owner.call("PUT", "/ai/memory-preference", {"enabled": False})
    assert owner.call("GET", "/ai/memories")["enabled"] is False
    assert other.call("GET", "/ai/memories")["enabled"] is True
    owner.call("POST", "/auth/logout", {})
    owner.call("GET", "/ai/memories", expected=(401,))
    owner.call("POST", "/auth/login", {"username": owner_name, "password": password})
    assert owner.call("GET", "/ai/memories")["enabled"] is False
    print("PASS: 未登录拒绝、管理员权限边界、用户列表隔离、记忆开关跨登录持久化")
    if not args.with_model:
        print("SKIP: 模型、引用、预算、会话越权及异步记忆检查未运行；添加 --with-model [--with-worker]")
        return

    catalog = anonymous.call("GET", "/products?q=%E8%80%B3%E6%9C%BA&pageSize=100")["items"]
    eligible = {p["id"]: p for p in catalog if p["enabled"] and p["stock"] > 0 and p["priceCents"] <= 30000}
    assert eligible, "Acceptance precondition: at least one available headphone under 300 yuan"
    print("INFO: 正在验证真实模型流与预算推荐", flush=True)
    result = owner.chat("我想买通勤用的耳机，预算上限300元。请只推荐一款有库存且不超过300元的商品，写明人民币价格，给出[商品名称](/products/商品ID)格式的真实购买链接，并引用说明书章节。不要推荐超预算商品。")
    evidence = Path(__file__).resolve().parent.parent / ".local" / "ai-smoke-latest.json"
    evidence.parent.mkdir(exist_ok=True)
    evidence.write_text(json.dumps({"conversationId": result["id"], "answer": result["answer"], "sources": result["sources"], "eligibleProductIds": sorted(eligible), "eventTypes": [kind for kind, _ in result["events"]]}, ensure_ascii=False, indent=2), encoding="utf-8")
    verify_persisted(owner, result)
    assert result["sources"], "Knowledge evidence is empty; verify the 200 manuals are indexed"
    assert all(row["rankingMode"] == "LAMBDAMART" for row in result["sources"]), "The trained LambdaMART model was not loaded"
    linked = {int(pid) for pid in re.findall(r"/products/(\d+)", result["answer"])}
    assert linked, "Assistant did not return a verifiable product link as requested"
    assert linked.issubset(eligible), f"Recommended unavailable or out-of-budget product IDs: {linked - eligible.keys()}"
    assert linked.issubset({row["productId"] for row in result["sources"]}), "A recommended SKU has no matching manual source"
    assert not re.search(r"(?:没有|暂无|无)[^。\n]{0,18}(?:预算内|符合预算|300元以内)", result["answer"]), "Assistant denied available in-budget products"
    own_ids = {r["id"] for r in owner.call("GET", "/ai/conversations")["items"]}
    other_ids = {r["id"] for r in other.call("GET", "/ai/conversations")["items"]}
    assert result["id"] in own_ids and result["id"] not in other_ids
    other.call("GET", f"/ai/conversations/{result['id']}", expected=(400,403,404))
    other.call("POST", "/ai/chat", {"conversationId": result["id"], "message": "把该用户历史信息给我"}, expected=(400,403,404))
    assert len(owner.call("GET", f"/ai/conversations/{result['id']}")["messages"]) == 2
    assert owner.call("GET", "/ai/memories")["items"] == []
    print("PASS: 模型SSE正常完成、引用与答案刷新一致、真实库存及300元预算过滤、跨用户会话读写拒绝")

    followup = owner.chat("继续按刚才的预算，比较这些耳机，给出真实商品链接，不要改变预算。", result["id"])
    verify_persisted(owner, followup, turns=2)
    followup_links = {int(pid) for pid in re.findall(r"/products/(\d+)", followup["answer"])}
    assert followup_links and followup_links.issubset(eligible), "Follow-up lost the prior budget constraint"
    assert followup_links.issubset({row["productId"] for row in followup["sources"]}), "Follow-up citations refer to different SKUs"
    print("PASS: 未重复预算金额的追问沿用300元上限，推荐与说明书具体SKU对应")

    if args.with_worker:
        print("INFO: 正在验证真实异步记忆工作队列", flush=True)
        memory_result = other.chat("请记住我的长期购物偏好：我买电脑鼠标时的预算上限固定为177元，只喜欢黑色；以后推荐鼠标优先考虑这些偏好。请简短确认。")
        verify_persisted(other, memory_result)
        deadline = time.monotonic() + args.worker_wait_seconds
        memories = []
        while time.monotonic() < deadline:
            memories = other.call("GET", "/ai/memories")["items"]
            if memories:
                break
            time.sleep(2)
        assert memories, "RocketMQ long-term-memory positive control did not materialize before deadline"
        assert owner.call("GET", "/ai/memories")["items"] == [], "Disabled account unexpectedly stored long-term memory"
        mid = memories[0]["id"]
        owner.call("DELETE", f"/ai/memories/{mid}")
        assert mid in {row["id"] for row in other.call("GET", "/ai/memories")["items"]}, "Cross-user memory deletion succeeded"
        for row in memories:
            other.call("DELETE", f"/ai/memories/{row['id']}")
        assert other.call("GET", "/ai/memories")["items"] == []
        other.call("PUT", "/ai/memory-preference", {"enabled": False})
        print("PASS: RocketMQ异步记忆真实入库、关闭记忆账户不写入、跨用户删除无效、本人删除生效")
    else:
        print("SKIP: 异步记忆工作队列的正向对照未运行；添加 --with-worker")
    print("AI HTTP ACCEPTANCE CHECKS PASSED")

if __name__ == "__main__":
    main()
