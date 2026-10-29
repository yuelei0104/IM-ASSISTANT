import { requestJson } from "./apiClient";

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
  return requestJson<T>(`${apiBase}/chats/notices${path}`, {
    method, body, fallback: (status) => `通知请求失败（HTTP ${status}）`,
  });
}

export const listMentionNotices = (beforeId?: number) =>
  request<NoticePage>(`?limit=50${beforeId ? `&beforeId=${beforeId}` : ""}`);
export const markMentionRead = (id: number) => request<void>(`/${id}/read`, "POST");
export const markAllMentionsRead = () => request<void>("/read-all", "POST");
export const getAlertKeywords = () => request<{ words: string[] }>("/keywords");
export const saveAlertKeywords = (words: string[]) =>
  request<{ words: string[] }>("/keywords", "PUT", { words });
