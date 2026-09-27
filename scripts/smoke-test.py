"""Exercise the real HTTP gateway, MySQL, Redis, Nacos and Dubbo with isolated demo users."""
import argparse
import http.cookiejar
import json
import os
import time
from urllib.error import HTTPError, URLError
from urllib.request import HTTPCookieProcessor, ProxyHandler, Request, build_opener
import uuid

parser = argparse.ArgumentParser()
parser.add_argument("--base-url", default="http://127.0.0.1:8080")
parser.add_argument("--wait-seconds", type=int, default=120)
args = parser.parse_args()
base = args.base_url.rstrip("/") + "/api"

class Client:
    def __init__(self):
        self.cookies = http.cookiejar.CookieJar()
        self.opener = build_opener(ProxyHandler({}), HTTPCookieProcessor(self.cookies))

    def call(self, method, path, body=None, expected=200):
        payload = None if body is None else json.dumps(body).encode()
        request = Request(base + path, data=payload, method=method,
                          headers={"Content-Type": "application/json", "Origin": os.environ.get("FRONTEND_ORIGIN", "http://localhost:3000")})
        try:
            with self.opener.open(request, timeout=30) as response:
                status, data = response.status, response.read()
        except HTTPError as error:
            status, data = error.code, error.read()
        allowed = {expected} if isinstance(expected, int) else set(expected)
        assert status in allowed, f"{method} {path}: expected {allowed}, got HTTP {status}; response {data.decode()[:300]}"
        return json.loads(data) if data else {}

anonymous, user, admin, other = Client(), Client(), Client(), Client()
deadline = time.monotonic() + args.wait_seconds
while True:
    try:
        products = anonymous.call("GET", "/products?pageSize=100")
        assert products["total"] == 200, f"Expected 200 seeded products, got {products['total']}"
        # Catalog may start before Commerce; only retry these read-only probes.
        anonymous.call("GET", "/auth/me", expected=401)
        break
    except (AssertionError, URLError, TimeoutError, ConnectionError):
        if time.monotonic() >= deadline:
            raise
        time.sleep(2)

nonce = uuid.uuid4().hex[:12]
password = "SmokeTest-" + uuid.uuid4().hex
registered = user.call("POST", "/auth/register", {"username": "smoke_" + nonce, "password": password, "nickname": "集成验证"}, expected=(200, 201))
user_id = registered["user"]["id"]
assert registered["user"]["balanceCents"] == 0
assert user.call("GET", "/auth/me")["id"] == user_id
anonymous.call("GET", "/wallet", expected=401)
user.call("GET", "/admin/users", expected=403)
other.call("POST", "/auth/register", {"username": "other_" + nonce, "password": password, "nickname": "隔离用户"}, expected=(200, 201))
admin.call("POST", "/auth/login", {"username": os.environ.get("ADMIN_USERNAME", "admin"), "password": os.environ["ADMIN_PASSWORD"]})
print("PASS: 注册、Redis 登录会话与管理员权限边界")

product = next(p for p in products["items"] if p["stock"] >= 2 and p["enabled"])
product_id, initial_stock, price = product["id"], product["stock"], product["priceCents"]
user.call("PUT", f"/cart/items/{product_id}", {"quantity": 2})
cart = user.call("GET", "/cart")
assert cart["totalCents"] == price * 2

order_input = {"items": [{"productId": product_id, "quantity": 2}],
               "address": {"recipient": "测试收件人", "phone": "13800000000", "detail": "本地集成验证，无实际发货"},
               "idempotencyKey": "smoke-order-" + nonce}
order = user.call("POST", "/orders", order_input, expected=(200, 201))
order_id = order["id"]
assert order["status"] == "PENDING_PAYMENT"
assert order["totalCents"] == price * 2
duplicate = user.call("POST", "/orders", order_input, expected=(200, 201))
assert duplicate["id"] == order_id
assert anonymous.call("GET", f"/products/{product_id}")["stock"] == initial_stock - 2
other.call("GET", f"/orders/{order_id}", expected=(403, 404))
user.call("POST", f"/orders/{order_id}/pay", {}, expected=(400, 409, 422))
assert user.call("GET", "/wallet")["balanceCents"] == 0
assert user.call("GET", f"/orders/{order_id}")["status"] == "PENDING_PAYMENT"
print("PASS: 购物车、Dubbo 商品校验、库存预留、订单幂等、订单隔离、余额不足回滚")

credit_amount = price * 3
credit = {"amountCents": credit_amount, "idempotencyKey": "smoke-credit-" + nonce, "reason": "自动集成验证"}
admin.call("POST", f"/admin/users/{user_id}/credit", credit)
admin.call("POST", f"/admin/users/{user_id}/credit", credit)
assert user.call("GET", "/wallet")["balanceCents"] == credit_amount
paid = user.call("POST", f"/orders/{order_id}/pay", {})
assert paid["status"] == "PAID"
user.call("POST", f"/orders/{order_id}/pay", {})
assert user.call("GET", "/wallet")["balanceCents"] == price
assert user.call("POST", f"/orders/{order_id}/refund", {})["status"] == "REFUNDED"
user.call("POST", f"/orders/{order_id}/refund", {})
wallet = user.call("GET", "/wallet")
assert wallet["balanceCents"] == credit_amount
assert len(wallet["ledger"]) == 3, "充值、支付、退款各应只产生一条账本"
assert anonymous.call("GET", f"/products/{product_id}")["stock"] == initial_stock
print("PASS: 充值/支付/退款幂等、账本唯一性、库存与余额恢复")

order_input["idempotencyKey"] = "smoke-cancel-" + nonce
cancel_order = user.call("POST", "/orders", order_input, expected=(200, 201))
assert user.call("POST", f"/orders/{cancel_order['id']}/cancel", {})["status"] == "CANCELLED"
user.call("POST", f"/orders/{cancel_order['id']}/cancel", {})
assert anonymous.call("GET", f"/products/{product_id}")["stock"] == initial_stock
user.call("POST", "/auth/logout", {})
user.call("GET", "/auth/me", expected=401)
print("PASS: 取消幂等、库存恢复、退出后凭证失效")
print("HTTP 真实集成验证全部通过。测试用户和审计记录保留，不删除业务数据。")
