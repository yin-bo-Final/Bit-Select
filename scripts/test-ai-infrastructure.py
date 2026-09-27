"""Real low-cost middleware checks. Does not invoke an AI model or persist user data."""
from datetime import datetime, timezone
import base64
import hashlib
import hmac
import json
import os
from urllib.parse import urlsplit
from urllib.request import ProxyHandler, Request, build_opener
import uuid

http = build_opener(ProxyHandler({}))
nonce = uuid.uuid4().hex

def request(method, url, body=b"", headers=None):
    with http.open(Request(url, data=body if method != "GET" else None, headers=headers or {}, method=method), timeout=60) as response:
        return response.read()

def post(url, body, headers=None):
    return json.loads(request("POST", url, json.dumps(body).encode(), {"Content-Type": "application/json", **(headers or {})}))

def s3(method, object_path, body=b""):
    endpoint = os.environ.get("RUSTFS_ENDPOINT", "http://127.0.0.1:19000").rstrip("/")
    now = datetime.now(timezone.utc)
    stamp, day = now.strftime("%Y%m%dT%H%M%SZ"), now.strftime("%Y%m%d")
    payload_hash = hashlib.sha256(body).hexdigest()
    headers = {"host": urlsplit(endpoint).netloc, "x-amz-content-sha256": payload_hash, "x-amz-date": stamp}
    names = ";".join(sorted(headers))
    canonical_headers = "".join(f"{key}:{headers[key]}\n" for key in sorted(headers))
    canonical = "\n".join((method, object_path, "", canonical_headers, names, payload_hash))
    scope = f"{day}/us-east-1/s3/aws4_request"
    message = "\n".join(("AWS4-HMAC-SHA256", stamp, scope, hashlib.sha256(canonical.encode()).hexdigest()))
    key = ("AWS4" + os.environ["RUSTFS_SECRET_KEY"]).encode()
    for value in (day, "us-east-1", "s3", "aws4_request"):
        key = hmac.new(key, value.encode(), hashlib.sha256).digest()
    signature = hmac.new(key, message.encode(), hashlib.sha256).hexdigest()
    headers["Authorization"] = f"AWS4-HMAC-SHA256 Credential={os.environ['RUSTFS_ACCESS_KEY']}/{scope}, SignedHeaders={names}, Signature={signature}"
    return request(method, endpoint + object_path, body, headers)

object_path = f"/bit-select-documents/integration/{nonce}.txt"
sample = "比特严选中间件集成验证：商品说明与向量检索。".encode()
try:
    s3("PUT", object_path, sample)
    assert s3("GET", object_path) == sample
    print("PASS: RustFS SigV4 S3 对象写入与读取")
finally:
    s3("DELETE", object_path)

tika = os.environ.get("TIKA_URL", "http://127.0.0.1:19998").rstrip("/")
parsed = request("PUT", tika + "/tika", sample, {"Content-Type": "text/plain; charset=utf-8", "Accept": "text/plain"}).decode()
assert "比特严选" in parsed
print("PASS: Tika 中文文档解析")

neo4j = "http://127.0.0.1:" + os.environ.get("NEO4J_HTTP_PORT", "17474") + "/db/neo4j/tx/commit"
credentials = base64.b64encode((os.environ.get("NEO4J_USER", "neo4j") + ":" + os.environ["NEO4J_PASSWORD"]).encode()).decode()
result = post(neo4j, {"statements": [{"statement": "CREATE (n:BitSelectIntegrationProbe {id: $id}) WITH n, n.id AS id DELETE n RETURN id", "parameters": {"id": nonce}}]}, {"Authorization": "Basic " + credentials})
assert not result["errors"], "Neo4j transaction failed"
assert result["results"][0]["data"][0]["row"][0] == nonce
print("PASS: Neo4j 认证、事务与节点读写")

milvus = os.environ.get("MILVUS_URI", "http://127.0.0.1:19530").rstrip("/") + "/v2/vectordb"
collection = "integration_probe_" + nonce
def vector(path, body):
    result = post(milvus + path, body)
    assert result.get("code") == 0, f"Milvus {path} failed: {result}"
    return result.get("data")

try:
    vector("/collections/create", {"collectionName": collection, "dimension": 4, "metricType": "COSINE", "consistencyLevel": "Strong"})
    vector("/entities/insert", {"collectionName": collection, "data": [{"id": 1, "vector": [1.0, 0.0, 0.0, 0.0], "source": "local-integration"}]})
    found = vector("/entities/search", {"collectionName": collection, "data": [[1.0, 0.0, 0.0, 0.0]], "limit": 1, "consistencyLevel": "Strong", "outputFields": ["source"]})
    assert found and found[0]["id"] == 1
    assert found[0]["distance"] > 0.999
    print("PASS: Milvus collection 创建、向量写入、余弦相似度检索")
finally:
    vector("/collections/drop", {"collectionName": collection})
print("AI 中间件真实集成验证通过；临时对象、节点、collection 均已清理。")
