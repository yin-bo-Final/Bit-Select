"""Concurrent HTTP checks against the real MySQL/Redis/Dubbo deployment.
Creates one isolated QA product and two users, cancels/refunds test orders,
then takes the QA product offline. Does not modify the 200 seed products.
"""
import concurrent.futures
import http.cookiejar
import json
import os
import time
import uuid
from urllib.error import HTTPError
from urllib.request import Request, build_opener, HTTPCookieProcessor, ProxyHandler

BASE = os.getenv("TEST_BASE_URL", "http://127.0.0.1:8080").rstrip("/") + "/api"
class Client:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.http = build_opener(ProxyHandler({}), HTTPCookieProcessor(self.jar))
    def call(self, method, path, body=None, expected=(200,)):
        for attempt in range(5):
            req = Request(BASE + path, method=method,
                data=None if body is None else json.dumps(body).encode(),
                headers={"Content-Type": "application/json", "Origin": "http://localhost:3000"})
            try:
                with self.http.open(req, timeout=30) as response:
                    status, data = response.status, response.read()
            except HTTPError as error:
                status, data = error.code, error.read()
            if status != 429 or attempt == 4:
                break
            time.sleep(0.35)
        assert status in expected, f"{method} {path}: HTTP {status}: {data[:250]!r}"
        return status, json.loads(data or b"{}")

def together(first, second):
    import threading
    barrier = threading.Barrier(2)
    def run(fn):
        barrier.wait(timeout=5)
        return fn()
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(run, first), pool.submit(run, second)]
        return [f.result(timeout=35) for f in futures]

def main():
    nonce = uuid.uuid4().hex[:12]
    password = "QaConcurrency-" + uuid.uuid4().hex
    admin, one, two = Client(), Client(), Client()
    admin.call("POST", "/auth/login", {"username": os.getenv("ADMIN_USERNAME", "admin"), "password": os.environ["ADMIN_PASSWORD"]})
    users = []
    for number, client in enumerate((one, two)):
        _, result = client.call("POST", "/auth/register", {"username": f"race_{nonce}_{number}", "password": password, "nickname": "并发事务验证"})
        users.append(result["user"]["id"])
    product = {"name": "自动验收商品 " + nonce, "category": "qa", "categoryName": "验证专用", "description": "自动化并发测试，测试后下架。", "priceCents": 100, "originalPriceCents": 100, "imageUrl": "/products/BS-0001.svg", "specifications": {}, "tags": ["验收"], "manualUrl": "", "enabled": True, "featured": False}
    _, p = admin.call("POST", "/admin/products", product)
    pid = p["id"]
    def inventory(delta):
        return admin.call("POST", f"/admin/products/{pid}/inventory", {"delta": delta, "reason": "并发验收", "idempotencyKey": uuid.uuid4().hex})
    def order(client):
        return client.call("POST", "/orders", {"items": [{"productId": pid, "quantity": 1}], "address": {"recipient": "验收", "phone": "13800000000", "detail": "本地验收，无实际发货"}, "idempotencyKey": uuid.uuid4().hex}, expected=(200,409))
    try:
        inventory(1)
        raced = together(lambda: order(one), lambda: order(two))
        assert sorted(code for code, _ in raced) == [200, 409]
        assert next(body for code, body in raced if code == 409)["code"] == "INSUFFICIENT_STOCK"
        assert one.call("GET", f"/products/{pid}")[1]["stock"] == 0
        for client, (code, body) in zip((one, two), raced):
            if code == 200:
                client.call("POST", f"/orders/{body['id']}/cancel", {})
        assert one.call("GET", f"/products/{pid}")[1]["stock"] == 1
        print("PASS: 两用户争抢最后1件库存，仅1单预留成功，取消后精确恢复库存")
        inventory(3)
        admin.call("POST", f"/admin/users/{users[0]}/credit", {"amountCents": 100, "reason": "并发验收", "idempotencyKey": uuid.uuid4().hex})
        a, b = order(one)[1]["id"], order(one)[1]["id"]
        paid = together(lambda: one.call("POST", f"/orders/{a}/pay", {}, expected=(200,409)), lambda: one.call("POST", f"/orders/{b}/pay", {}, expected=(200,409)))
        assert sorted(code for code, _ in paid) == [200,409]
        assert next(body for code, body in paid if code == 409)["code"] == "INSUFFICIENT_BALANCE"
        assert one.call("GET", "/wallet")[1]["balanceCents"] == 0
        for oid, (code, _) in zip((a,b), paid):
            one.call("POST", f"/orders/{oid}/" + ("refund" if code == 200 else "cancel"), {})
        print("PASS: 同钱包两个订单并发支付，仅1次成功，余额不透支")
        oid = order(one)[1]["id"]
        twice = together(lambda: one.call("POST", f"/orders/{oid}/pay", {}), lambda: one.call("POST", f"/orders/{oid}/pay", {}))
        assert all(code == 200 for code, _ in twice)
        wallet = one.call("GET", "/wallet")[1]
        assert wallet["balanceCents"] == 0
        assert len([row for row in wallet["ledger"] if row["type"] == "PAYMENT" and row["referenceId"] == oid]) == 1
        two.call("GET", f"/orders/{oid}", expected=(404,))
        one.call("POST", f"/orders/{oid}/refund", {})
        assert one.call("GET", f"/products/{pid}")[1]["stock"] == 4
        print("PASS: 同一订单并发支付返回一致，账本只扣一次，退款库存及余额一致")
    finally:
        product["enabled"] = False
        admin.call("PUT", f"/admin/products/{pid}", product)
    print("ALL REAL CONCURRENCY CHECKS PASSED")

if __name__ == "__main__":
    main()
