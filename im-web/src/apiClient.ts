import { expireIfCurrent, sessionToken } from "./session";

export class ApiError extends Error {
  readonly status: number;
  constructor(message: string, status: number) {
    super(message);
    this.status = status;
  }
}

type Options = {
  method?: string;
  body?: object;
  token?: string | null;
  requireAuth?: boolean;
  timeoutMs?: number;
  emptyStatuses?: number[];
  fallback?: (status: number) => string;
};

/** Shared transport for auth, contacts, messages, groups, notices and tasks. */
export async function requestJson<T>(path: string, options: Options = {}): Promise<T> {
  const token = options.token === undefined ? sessionToken() : options.token;
  if (options.requireAuth !== false && !token) throw new ApiError("请先登录", 401);
  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => { timedOut = true; controller.abort(); }, options.timeoutMs ?? 15_000);
  let response: Response;
  try {
    response = await fetch(path, {
      method: options.method || (options.body === undefined ? "GET" : "POST"),
      headers: {
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(options.body !== undefined ? { "Content-Type": "application/json" } : {}),
      },
      ...(options.body !== undefined ? { body: JSON.stringify(options.body) } : {}),
      signal: controller.signal,
    });
  } catch {
    throw new ApiError(timedOut ? "请求超时，请稍后重试" : "无法连接服务，请检查网络与后端状态", 0);
  } finally {
    clearTimeout(timer);
  }
  if (response.status === 401 && token) expireIfCurrent(token);
  if (response.status === 204 || options.emptyStatuses?.includes(response.status))
    return undefined as T;
  let payload: unknown;
  try { payload = await response.json(); }
  catch {
    throw new ApiError(`服务返回 HTTP ${response.status}，响应格式无法识别；请检查服务状态`, response.status);
  }
  if (!response.ok) {
    const serverMessage = (payload as { message?: unknown } | null)?.message;
    throw new ApiError(typeof serverMessage === "string" ? serverMessage
      : options.fallback?.(response.status) || `请求失败（HTTP ${response.status}）`, response.status);
  }
  return payload as T;
}
