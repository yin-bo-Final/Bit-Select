import test from "node:test";
import assert from "node:assert/strict";
import { ApiError } from "../src/lib/api.ts";
import { cancelChatRequest, openChatStream } from "../src/lib/chat-request.ts";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
const locked = () =>
  json({ code: "CONVERSATION_BUSY", message: "这段会话仍在生成回答" }, 409);

test("same conversation waits for a cancelling worker, then opens exactly one stream", async (t) => {
  const requests: { url: unknown; options?: RequestInit }[] = [];
  let waiting = 0;
  t.mock.method(
    globalThis,
    "fetch",
    async (url: RequestInfo | URL, options?: RequestInit) => {
      requests.push({ url, options });
      return requests.length === 1
        ? locked()
        : new Response("event: done\ndata: {}\n\n", {
            headers: { "Content-Type": "text/event-stream" },
          });
    },
  );
  const response = await openChatStream(
    "existing-conversation",
    "重新回答",
    new AbortController().signal,
    () => waiting++,
  );
  assert.equal(response.status, 200);
  assert.equal(requests.length, 2);
  assert.equal(waiting, 1);
  assert.equal(requests[0].options?.body, requests[1].options?.body);
  assert.equal(
    (requests[0].options?.headers as Record<string, string>).Accept,
    "text/event-stream, application/json",
  );
  assert.equal(
    JSON.parse(String(requests[1].options?.body)).conversationId,
    "existing-conversation",
  );
});

test("stopping during lock cleanup aborts the retry instead of sending another request", async (t) => {
  const controller = new AbortController();
  const fetch = t.mock.method(globalThis, "fetch", async () => locked());
  await assert.rejects(
    openChatStream("conversation", "question", controller.signal, () =>
      controller.abort(),
    ),
    { name: "AbortError" },
  );
  assert.equal(fetch.mock.callCount(), 1);
});

test("unrelated conflicts, capacity limits and model failures are never automatically replayed", async (t) => {
  for (const [status, code] of [
    [409, "CONFLICT"],
    [429, "AI_BUSY"],
    [503, "MODEL_UNCONFIGURED"],
  ] as const) {
    const fetch = t.mock.method(globalThis, "fetch", async () =>
      json({ code, message: "具体原因" }, status),
    );
    await assert.rejects(
      openChatStream(
        "conversation",
        "question",
        new AbortController().signal,
        () => assert.fail("Unexpected wait"),
      ),
      (failure) =>
        failure instanceof ApiError &&
        failure.status === status &&
        failure.message === "具体原因",
    );
    assert.equal(fetch.mock.callCount(), 1);
    fetch.mock.restore();
  }
});

test("a busy lock cannot cause endless automatic retries", async (t) => {
  let reads = 0;
  t.mock.method(Date, "now", () => (reads++ ? 21000 : 0));
  const fetch = t.mock.method(globalThis, "fetch", async () => locked());
  await assert.rejects(
    openChatStream(
      "conversation",
      "question",
      new AbortController().signal,
      () => assert.fail("Deadline passed"),
    ),
    (failure) => failure instanceof ApiError && failure.status === 409,
  );
  assert.equal(fetch.mock.callCount(), 1);
});

test("an accepted stream is returned without replaying its possible terminal error", async (t) => {
  const fetch = t.mock.method(
    globalThis,
    "fetch",
    async () =>
      new Response('event: error\ndata: {"message":"模型失败"}\n\n', {
        headers: { "Content-Type": "text/event-stream" },
      }),
  );
  const response = await openChatStream(
    "conversation",
    "question",
    new AbortController().signal,
    () => assert.fail(),
  );
  assert.match(await response.text(), /模型失败/);
  assert.equal(fetch.mock.callCount(), 1);
});

test("stop confirmation remains pending until the server actually releases the worker", async (t) => {
  const requests: string[] = [];
  t.mock.method(globalThis, "fetch", async (url: RequestInfo | URL) => {
    requests.push(String(url));
    return requests.length === 1
      ? json({ finished: false }, 202)
      : json({ finished: true });
  });
  await cancelChatRequest("request-id");
  assert.deepEqual(requests, [
    "/api/ai/requests/request-id/cancel",
    "/api/ai/requests/request-id/cancel",
  ]);
});

test("a foreign request cannot be stopped and is not polled again", async (t) => {
  const fetch = t.mock.method(globalThis, "fetch", async () =>
    json({ code: "NOT_FOUND", message: "资源不存在" }, 404),
  );
  await assert.rejects(
    cancelChatRequest("foreign-request"),
    (failure) => failure instanceof ApiError && failure.status === 404,
  );
  assert.equal(fetch.mock.callCount(), 1);
});

test("a malformed confirmation fails instead of reporting completion", async (t) => {
  const fetch = t.mock.method(globalThis, "fetch", async () =>
    json({ finished: false }),
  );
  await assert.rejects(cancelChatRequest("request-id"), /停止确认格式异常/);
  assert.equal(fetch.mock.callCount(), 1);
});
