export type StreamEvent = { event: string; data: string };

const MAX_EVENT_CHARACTERS = 1024 * 1024;

// Parse UTF-8 incrementally: CR, LF and CRLF can all cross network boundaries.
// An event is dispatched only after its blank line, never from an incomplete EOF.
export async function* readEvents(
  stream: ReadableStream<Uint8Array>,
  signal?: AbortSignal,
): AsyncGenerator<StreamEvent> {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let line = "";
  let event = "message";
  let data: string[] = [];
  let skipLf = false;
  let eventCharacters = 0;
  let ended = false;
  const cancel = () => {
    void reader.cancel(signal?.reason).catch(() => {});
  };

  const consumeLine = (): StreamEvent | undefined => {
    const current = line;
    line = "";
    if (current === "") {
      const result = data.length
        ? { event: event || "message", data: data.join("\n") }
        : undefined;
      event = "message";
      data = [];
      eventCharacters = 0;
      return result;
    }
    if (current.startsWith(":")) return;
    const colon = current.indexOf(":");
    const field = colon === -1 ? current : current.slice(0, colon);
    let value = colon === -1 ? "" : current.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    if (field === "event") event = value;
    if (field === "data") data.push(value);
  };

  signal?.addEventListener("abort", cancel, { once: true });
  try {
    signal?.throwIfAborted();
    while (true) {
      const { value, done } = await reader.read();
      signal?.throwIfAborted();
      const text = decoder.decode(value, { stream: !done });
      for (const character of text) {
        if (skipLf) {
          skipLf = false;
          if (character === "\n") continue;
        }
        if (character === "\r" || character === "\n") {
          skipLf = character === "\r";
          const result = consumeLine();
          if (result) yield result;
        } else {
          eventCharacters += character.length;
          if (eventCharacters > MAX_EVENT_CHARACTERS)
            throw new Error("回答数据异常，请重新发送。");
          line += character;
        }
      }
      if (done) {
        ended = true;
        break;
      }
    }
  } finally {
    signal?.removeEventListener("abort", cancel);
    if (!ended) await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}
