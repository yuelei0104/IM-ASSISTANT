import { ApiError, sessionToken } from "./authApi";

export type MentionNotice = {
  id: number;
  groupId: number;
  messageId: number;
  senderId: number;
  groupName: string;
  body: string;
  createdAt: string;
  readAt: string | null;
  kind: "MENTION" | "KEYWORD";
};
export type NoticePage = {
  items: MentionNotice[];
  nextBeforeId: number | null;
  unreadCount: number;
};
const apiBase = (import.meta.env?.VITE_API_BASE_URL || "/api").replace(/\/$/, "");

async function request<T>(path: string, method: "GET" | "POST" | "PUT" = "GET", body?: object): Promise<T> {
  const token = sessionToken();
  if (!token) throw new ApiError("请先登录", 401);
  let response: Response;
  try {
    response = await fetch(`${apiBase}/chats/notices${path}`, {
      method,
      headers: { Authorization: `Bearer ${token}`, ...(body ? { "Content-Type": "application/json" } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });
  } catch {
    throw new ApiError("无法连接消息服务，请确认 8082 端口已启动", 0);
  }
  if (!response.ok) {
    const payload = await response.json().catch(() => null);
    throw new ApiError(payload && typeof payload.message === "string"
      ? payload.message : `通知请求失败（HTTP ${response.status}）`, response.status);
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>;
}

export const listMentionNotices = (beforeId?: number) =>
  request<NoticePage>(`?limit=50${beforeId ? `&beforeId=${beforeId}` : ""}`);
export const markMentionRead = (id: number) => request<void>(`/${id}/read`, "POST");
export const markAllMentionsRead = () => request<void>("/read-all", "POST");
export const getAlertKeywords = () => request<{ words: string[] }>("/keywords");
export const saveAlertKeywords = (words: string[]) =>
  request<{ words: string[] }>("/keywords", "PUT", { words });
