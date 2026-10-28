import { ApiError, sessionToken } from "./authApi";

export type DirectMessage = {
  id: number;
  senderId: number;
  recipientId: number;
  clientMsgId: string;
  body: string;
  createdAt: string;
  recalledAt: string | null;
};
export type MessagePage = { messages: DirectMessage[]; nextBeforeId: number | null };
export type DirectSyncPage = { messages: DirectMessage[]; nextAfterId: number | null };
export type UnreadCount = { peerId: number; unreadCount: number };
export type ReadReceipt = { lastReadMessageId: number };
export type MessageRecall = { id: number; messageId: number; recalledAt: string };
export type RecallPage = { items: MessageRecall[]; nextAfterId: number | null };

const apiBase = (import.meta.env?.VITE_API_BASE_URL || "/api").replace(/\/$/, "");

async function request<T>(path: string, body?: object, method?: "DELETE"): Promise<T> {
  const token = sessionToken();
  if (!token) throw new ApiError("请先登录", 401);
  let response: Response;
  try {
    response = await fetch(`${apiBase}/chats/direct${path}`, {
      method: method || (body ? "POST" : "GET"),
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

export function loadDirectMessages(peerId: number, beforeId?: number, limit = 50) {
  const query = beforeId ? `?beforeId=${beforeId}&limit=${limit}` : `?limit=${limit}`;
  return request<MessagePage>(`/${peerId}/messages${query}`);
}

export function syncDirectMessages(peerId: number, afterId: number, limit = 50) {
  return request<DirectSyncPage>(`/${peerId}/messages/sync?afterId=${afterId}&limit=${limit}`);
}

export function searchDirectMessages(peerId: number, query: string, beforeId?: number) {
  return request<MessagePage>(`/${peerId}/messages/search?q=${encodeURIComponent(query)}&limit=20${beforeId ? `&beforeId=${beforeId}` : ""}`);
}

export function sendDirectMessage(peerId: number, clientMsgId: string, body: string) {
  return request<DirectMessage>(`/${peerId}/messages`, { clientMsgId, body });
}
export const recallDirectMessage = (peerId: number, messageId: number) =>
  request<void>(`/${peerId}/messages/${messageId}`, undefined, "DELETE");
export const listDirectRecalls = (peerId: number, afterId: number) =>
  request<RecallPage>(`/${peerId}/messages/recalls?afterId=${afterId}&limit=50`);

export function listUnreadDirect() {
  return request<UnreadCount[]>("/unread");
}

export const listHiddenDirectConversations = () => request<number[]>("/conversations/hidden");
export const clearDirectConversation = (peerId: number) =>
  request<void>(`/conversations/${peerId}`, undefined, "DELETE");

export function markDirectRead(peerId: number, lastReadMessageId: number) {
  return request<void>(`/${peerId}/read`, { lastReadMessageId });
}

export function getDirectReadReceipt(peerId: number) {
  return request<ReadReceipt>(`/${peerId}/receipt`);
}

export function connectChatSocket(onUpdate: (kind?: "direct" | "group" | "notice" | "receipt", subjectId?: number) => void) {
  let socket: WebSocket | null = null;
  let authenticated = false;
  let stopped = false;
  let retry = 1000;
  let retryTimer: ReturnType<typeof setTimeout> | null = null;
  let reauthTimer: ReturnType<typeof setInterval> | null = null;

  function open() {
    if (stopped || !sessionToken()) return;
    const url = new URL(`${apiBase}/chats/ws`, window.location.href);
    url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
    const client = new WebSocket(url);
    socket = client;
    client.onopen = () => {
      const token = sessionToken();
      if (!token) { client.close(); return; }
      client.send(`AUTH ${token}`);
      reauthTimer = setInterval(() => {
        const current = sessionToken();
        if (!current) client.close();
        else if (client.readyState === WebSocket.OPEN) client.send(`AUTH ${current}`);
      }, 5 * 60_000);
    };
    client.onmessage = (event) => {
      if (event.data === "READY") {
        authenticated = true;
        retry = 1000;
        onUpdate(); // Fetch after reconnect to fill any gap in Pub/Sub delivery.
      } else if (/^MSG \d+ \d+ \d+$/.test(event.data)) {
        onUpdate("direct");
      } else if (/^READ [1-9]\d* [1-9]\d*$/.test(event.data)) {
        onUpdate("receipt", Number(event.data.split(" ")[1]));
      } else if (/^GROUP \d+ \d+$/.test(event.data)) {
        const [, rawGroupId, rawMessageId] = event.data.split(" ");
        onUpdate("group", rawMessageId === "0" ? Number(rawGroupId) : undefined);
      } else if (event.data === "NOTICE") {
        onUpdate("notice");
      }
    };
    client.onclose = () => {
      if (socket !== client) return;
      socket = null;
      authenticated = false;
      if (reauthTimer) clearInterval(reauthTimer);
      reauthTimer = null;
      if (stopped || !sessionToken()) return;
      retryTimer = setTimeout(open, retry + Math.floor(Math.random() * 250));
      retry = Math.min(30_000, retry * 2);
    };
    client.onerror = () => client.close();
  }

  open();
  return {
    connected: () => authenticated,
    close: () => {
      stopped = true;
      authenticated = false;
      if (retryTimer) clearTimeout(retryTimer);
      if (reauthTimer) clearInterval(reauthTimer);
      socket?.close();
      socket = null;
    },
  };
}
