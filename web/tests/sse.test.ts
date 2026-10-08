import test from "node:test";
import assert from "node:assert/strict";
import { readEvents } from "../src/lib/sse.ts";

const stream = (parts: Uint8Array[]) =>
  new ReadableStream<Uint8Array>({
    start(controller) {
      for (const part of parts) controller.enqueue(part);
      controller.close();
    },
  });
test("SSE parser preserves Chinese characters and CRLF split across network frames", async () => {
  const encoded = new TextEncoder().encode(
    'event: meta\r\ndata: {"conversationId":"abc"}\r\n\r\nevent: delta\r\ndata: {"content":"适合通勤"}\r\n\r\nevent: done\ndata: {}\n\n',
  );
  const events = [];
  for await (const event of readEvents(
    stream(Array.from(encoded, (byte) => new Uint8Array([byte]))),
  ))
    events.push(event);
  assert.deepEqual(
    events.map((event) => event.event),
    ["meta", "delta", "done"],
  );
  assert.equal(JSON.parse(events[1].data).content, "适合通勤");
});
test("SSE ignores heartbeat comments and joins multiline data", async () => {
  const events = [];
  for await (const event of readEvents(
    stream([
      new TextEncoder().encode(
        ": heartbeat\n\nevent: delta\ndata: first\ndata: second\n\n",
      ),
    ]),
  ))
    events.push(event);
  assert.deepEqual(events, [{ event: "delta", data: "first\nsecond" }]);
});

test("SSE dispatches a delta while the upstream connection is still open", async () => {
  let source!: ReadableStreamDefaultController<Uint8Array>;
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      source = controller;
      controller.enqueue(
        new TextEncoder().encode(
          'event: delta\ndata: {"content":"先到达"}\n\n',
        ),
      );
    },
  });
  const events = readEvents(body);
  assert.deepEqual(await events.next(), {
    done: false,
    value: { event: "delta", data: '{"content":"先到达"}' },
  });
  source.enqueue(new TextEncoder().encode("event: done\ndata: {}\n\n"));
  source.close();
  assert.equal((await events.next()).value?.event, "done");
  assert.equal((await events.next()).done, true);
});

test("SSE accepts CR-only lines, BOM, empty data and unknown fields", async () => {
  const encoded = new TextEncoder().encode(
    "\uFEFF: heartbeat\r\rretry: 1000\revent:\rid: abc\rdata\rdata:  padded\r\r",
  );
  const events = [];
  for await (const event of readEvents(
    stream(Array.from(encoded, (byte) => new Uint8Array([byte]))),
  ))
    events.push(event);
  assert.deepEqual(events, [{ event: "message", data: "\n padded" }]);
});

test("SSE discards an unterminated frame at EOF", async () => {
  const events = [];
  for await (const event of readEvents(
    stream([new TextEncoder().encode("event: done\ndata: {}\n")]),
  ))
    events.push(event);
  assert.deepEqual(events, []);
});

test("leaving the event loop cancels the open response body", async () => {
  let cancelled = false;
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(new TextEncoder().encode("event: done\ndata: {}\n\n"));
    },
    cancel() {
      cancelled = true;
    },
  });
  for await (const event of readEvents(body)) {
    assert.equal(event.event, "done");
    break;
  }
  assert.equal(cancelled, true);
  assert.equal(body.locked, false);
});

test("abort cancels a pending read and releases the response lock", async () => {
  const abort = new AbortController();
  let cancelled = false;
  const body = new ReadableStream<Uint8Array>({
    cancel() {
      cancelled = true;
    },
  });
  const events = readEvents(body, abort.signal);
  const pending = events.next();
  abort.abort();
  await assert.rejects(pending, { name: "AbortError" });
  assert.equal(cancelled, true);
  assert.equal(body.locked, false);
});

test("oversized frames fail instead of accumulating an unbounded buffer", async () => {
  const body = stream([
    new TextEncoder().encode(`data: ${"x".repeat(1024 * 1024)}\n\n`),
  ]);
  await assert.rejects(async () => {
    for await (const event of readEvents(body)) void event;
  }, /回答数据异常/);
  assert.equal(body.locked, false);
});
