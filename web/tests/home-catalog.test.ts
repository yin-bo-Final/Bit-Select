import test from "node:test";
import assert from "node:assert/strict";
import { loadHomeCatalog } from "../src/lib/home-catalog.ts";

const item = {
  id: 1,
  name: "无线耳机",
  category: "audio",
  categoryName: "音频与智能",
  description: "用于日常聆听。",
  priceCents: 12900,
  stock: 20,
  imageUrl: "/products/BS-0001.svg",
  featured: true,
  enabled: true,
};
const taxonomy = [{ id: "audio", name: "音频与智能", count: 20 }];
const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  });

test("server hero and categories start loading in parallel without account credentials", async (t) => {
  const requests: { url: string; options?: RequestInit }[] = [];
  const pending: ((response: Response) => void)[] = [];
  t.mock.method(
    globalThis,
    "fetch",
    (url: RequestInfo | URL, options?: RequestInit) => {
      requests.push({ url: String(url), options });
      return new Promise<Response>((resolve) => pending.push(resolve));
    },
  );
  const snapshot = loadHomeCatalog("http://gateway.local/");
  assert.equal(requests.length, 2);
  assert.equal(
    requests[0].url,
    "http://gateway.local/api/products?page=1&pageSize=12&sort=featured",
  );
  assert.equal(requests[1].url, "http://gateway.local/api/categories");
  for (const request of requests) {
    assert.equal(request.options?.cache, "no-store");
    assert.equal(request.options?.credentials, undefined);
    assert.deepEqual(request.options?.headers, { Accept: "application/json" });
    assert.ok(request.options?.signal instanceof AbortSignal);
  }
  pending[0](json({ data: { items: [item], total: 20 } }));
  pending[1](json(taxonomy));
  const result = await snapshot;
  assert.equal(result.products?.items[0].imageUrl, item.imageUrl);
  assert.equal(result.products?.items[0].stock, 20);
  assert.deepEqual(result.categories, taxonomy);
});

test("a timeout or unavailable catalog preserves independently loaded categories for client recovery", async (t) => {
  for (const failure of ["timeout", "unavailable"] as const) {
    const fetch = t.mock.method(
      globalThis,
      "fetch",
      async (url: RequestInfo | URL) => {
        if (String(url).endsWith("/categories")) return json(taxonomy);
        if (failure === "timeout")
          throw new DOMException("Slow catalog", "TimeoutError");
        return json({ message: "暂时不可用" }, 503);
      },
    );
    const result = await loadHomeCatalog();
    assert.equal(result.products, null);
    assert.deepEqual(result.categories, taxonomy);
    fetch.mock.restore();
  }
});

test("invalid or truncated public payloads do not become a crashing server snapshot", async (t) => {
  const fetch = t.mock.method(
    globalThis,
    "fetch",
    async (url: RequestInfo | URL) =>
      String(url).endsWith("/categories")
        ? new Response("{broken json", { status: 200 })
        : json({ items: [{ ...item, priceCents: "12900" }], total: 20 }),
  );
  assert.deepEqual(await loadHomeCatalog(), { products: null, categories: [] });
  fetch.mock.restore();
  t.mock.method(globalThis, "fetch", async (url: RequestInfo | URL) =>
    String(url).endsWith("/categories")
      ? json([{ id: "audio", name: "音频与智能", count: -1 }])
      : json({ items: [item], total: 20 }),
  );
  const partial = await loadHomeCatalog();
  assert.equal(partial.products?.items.length, 1);
  assert.deepEqual(partial.categories, []);
});
