export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}

export async function api<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api${path}`, {
      ...options,
      signal: options.signal
        ? AbortSignal.any([options.signal, AbortSignal.timeout(20000)])
        : AbortSignal.timeout(20000),
      credentials: "include",
      cache: "no-store",
      headers: { "Content-Type": "application/json", ...options.headers },
    });
  } catch (error) {
    if (error instanceof Error && error.name === "AbortError") throw error;
    throw new ApiError(
      error instanceof Error && error.name === "TimeoutError"
        ? "服务响应超时，请稍后重试。"
        : "暂时无法连接服务，请稍后重试。",
      0,
    );
  }
  const body = await response.json().catch(() => null);
  if (!response.ok)
    throw new ApiError(
      body?.message ||
        body?.error ||
        (response.status === 401
          ? "登录已过期，请重新登录。"
          : "操作未成功，请稍后重试。"),
      response.status,
    );
  return (body && "data" in body ? body.data : body) as T;
}
export const post = <T>(path: string, data?: unknown, options?: RequestInit) =>
  api<T>(path, {
    method: "POST",
    body: data === undefined ? undefined : JSON.stringify(data),
    ...options,
  });
export const money = (cents: number | undefined) =>
  new Intl.NumberFormat("zh-CN", { style: "currency", currency: "CNY" }).format(
    Number(cents || 0) / 100,
  );
export const date = (value: string) =>
  new Date(value).toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
export const errorText = (e: unknown) =>
  e instanceof Error ? e.message : "操作未成功，请稍后重试。";
