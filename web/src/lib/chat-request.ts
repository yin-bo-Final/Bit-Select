import { ApiError } from "./api";

const cleanupTimeoutMs = 20000;
const retryDelayMs = 350;

export type ActiveChatRequest = {
  id: string;
  controller: AbortController;
  serverRequestId?: string;
  cancellation?: Promise<void>;
};

async function wait(signal: AbortSignal) {
  signal.throwIfAborted();
  await new Promise<void>((resolve, reject) => {
    const abort = () => {
      clearTimeout(timer);
      reject(signal.reason);
    };
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", abort);
      resolve();
    }, retryDelayMs);
    signal.addEventListener("abort", abort, { once: true });
  });
}

async function error(response: Response) {
  const body = await response.json().catch(() => null);
  return {
    code: typeof body?.code === "string" ? body.code : "",
    message:
      typeof body?.message === "string"
        ? body.message
        : response.status === 409
          ? "上一轮回答仍在结束，请稍后重新回答。"
          : "导购暂时无法回答，请稍后重试。",
  };
}

/** Only the pre-stream conversation lock conflict is safe to retry automatically. */
export async function openChatStream(
  conversationId: string | null,
  message: string,
  signal: AbortSignal,
  onWaiting: () => void,
) {
  const deadline = Date.now() + cleanupTimeoutMs;
  while (true) {
    signal.throwIfAborted();
    const response = await fetch("/api/ai/chat", {
      method: "POST",
      credentials: "include",
      headers: {
        "Content-Type": "application/json",
        Accept: "text/event-stream, application/json",
      },
      body: JSON.stringify({ conversationId, message }),
      signal,
    });
    if (response.ok) return response;
    const failure = await error(response);
    if (
      conversationId &&
      response.status === 409 &&
      failure.code === "CONVERSATION_BUSY" &&
      Date.now() < deadline
    ) {
      onWaiting();
      await wait(signal);
      continue;
    }
    throw new ApiError(failure.message, response.status);
  }
}

/** The server acknowledges completion only after the worker releases its lock and permit. */
export async function cancelChatRequest(requestId: string) {
  const signal = AbortSignal.timeout(cleanupTimeoutMs);
  try {
    while (true) {
      signal.throwIfAborted();
      const response = await fetch(
        `/api/ai/requests/${encodeURIComponent(requestId)}/cancel`,
        {
          method: "POST",
          credentials: "include",
          headers: { Accept: "application/json" },
          signal,
        },
      );
      if (!response.ok) {
        const failure = await error(response);
        throw new ApiError(failure.message, response.status);
      }
      const body = await response.json();
      if (body?.finished === true) return;
      if (response.status !== 202 || body?.finished !== false)
        throw new Error("停止确认格式异常，请稍后重新回答。");
      await wait(signal);
    }
  } catch (failure) {
    if (signal.aborted) throw new Error("上一轮回答仍在结束，请稍后重新回答。");
    throw failure;
  }
}
