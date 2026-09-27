"""Create local S3 buckets using SigV4 without installing an additional client."""
from datetime import datetime, timezone
import hashlib
import hmac
import os
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

endpoint = os.environ["S3_ENDPOINT"].rstrip("/")
access_key = os.environ["RUSTFS_ACCESS_KEY"]
secret_key = os.environ["RUSTFS_SECRET_KEY"]

def sign(key, value):
    return hmac.new(key, value.encode(), hashlib.sha256).digest()

for bucket in ("bit-select-milvus", "bit-select-documents"):
    now = datetime.now(timezone.utc)
    amzdate, date = now.strftime("%Y%m%dT%H%M%SZ"), now.strftime("%Y%m%d")
    host = urlsplit(endpoint).netloc
    payload_hash = hashlib.sha256(b"").hexdigest()
    headers = {"host": host, "x-amz-content-sha256": payload_hash, "x-amz-date": amzdate}
    signed_headers = ";".join(sorted(headers))
    canonical_headers = "".join(f"{key}:{headers[key]}\n" for key in sorted(headers))
    canonical = "\n".join(("PUT", f"/{bucket}", "", canonical_headers, signed_headers, payload_hash))
    scope = f"{date}/us-east-1/s3/aws4_request"
    string_to_sign = "\n".join(("AWS4-HMAC-SHA256", amzdate, scope, hashlib.sha256(canonical.encode()).hexdigest()))
    signing_key = sign(sign(sign(sign(("AWS4" + secret_key).encode(), date), "us-east-1"), "s3"), "aws4_request")
    signature = hmac.new(signing_key, string_to_sign.encode(), hashlib.sha256).hexdigest()
    headers["Authorization"] = f"AWS4-HMAC-SHA256 Credential={access_key}/{scope}, SignedHeaders={signed_headers}, Signature={signature}"
    try:
        with urlopen(Request(f"{endpoint}/{bucket}", data=b"", headers=headers, method="PUT"), timeout=30) as response:
            print(f"Bucket ready: {bucket} (HTTP {response.status})")
    except HTTPError as error:
        body = error.read().decode("utf-8", "replace")
        if error.code == 409 and "BucketAlreadyOwnedByYou" in body:
            print(f"Bucket already exists: {bucket}")
        else:
            raise RuntimeError(f"Bucket initialization failed: {bucket}, HTTP {error.code}") from None
