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
