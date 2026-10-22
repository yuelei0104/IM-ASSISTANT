import { ApiError, sessionToken } from "./authApi";

export type DirectMessage = {
  id: number;
  senderId: number;
  recipientId: number;
  clientMsgId: string;
  body: string;
  createdAt: string;
};
export type MessagePage = { messages: DirectMessage[]; nextBeforeId: number | null };
export type UnreadCount = { peerId: number; unreadCount: number };

const apiBase = (import.meta.env?.VITE_API_BASE_URL || "/api").replace(/\/$/, "");

async function request<T>(path: string, body?: object): Promise<T> {
  const token = sessionToken();
  if (!token) throw new ApiError("请先登录", 401);
  let response: Response;
  try {
    response = await fetch(`${apiBase}/chats/direct${path}`, {
      method: body ? "POST" : "GET",
      headers: {
        Authorization: `Bearer ${token}`,
        ...(body ? { "Content-Type": "application/json" } : {}),
      },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });
  } catch {
    throw new ApiError("无法连接消息服务，请确认 8082 端口已启动", 0);
  }
  if (!response.ok) {
    const payload = await response.json().catch(() => null);
    const serverMessage = payload && typeof payload.message === "string"
      ? payload.message : null;
    const fallback = response.status === 401
      ? "登录已过期，请重新登录"
      : response.status === 403
        ? "无权访问此会话"
        : response.status >= 500
          ? `消息请求失败（HTTP ${response.status}），请检查 8082 服务窗口和 Vite 代理日志`
          : `消息请求失败（HTTP ${response.status}）`;
    throw new ApiError(serverMessage || fallback, response.status);
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>;
}

export function loadDirectMessages(peerId: number, beforeId?: number) {
  const query = beforeId ? `?beforeId=${beforeId}&limit=50` : "?limit=50";
  return request<MessagePage>(`/${peerId}/messages${query}`);
}

export function sendDirectMessage(peerId: number, clientMsgId: string, body: string) {
  return request<DirectMessage>(`/${peerId}/messages`, { clientMsgId, body });
}

export function listUnreadDirect() {
  return request<UnreadCount[]>("/unread");
}

export function markDirectRead(peerId: number, lastReadMessageId: number) {
  return request<void>(`/${peerId}/read`, { lastReadMessageId });
}
