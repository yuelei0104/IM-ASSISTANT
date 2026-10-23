import { ApiError, sessionToken } from "./authApi";

export type GroupChat = {
  id: number;
  name: string;
  ownerId: number;
  memberIds: number[];
  unreadCount: number;
};
export type GroupMessage = {
  id: number;
  groupId: number;
  senderId: number;
  clientMsgId: string;
  body: string;
  createdAt: string;
};
export type GroupPage = { messages: GroupMessage[]; nextBeforeId: number | null };
export type GroupSyncPage = { messages: GroupMessage[]; nextAfterId: number | null };
export type GroupSummary = {
  id: number;
  groupId: number;
  requesterId: number;
  firstMessageId: number;
  lastMessageId: number;
  content: string;
  createdAt: string;
};
export type GroupsPage = { items: GroupChat[]; nextBeforeId: number | null };
const apiBase = (import.meta.env?.VITE_API_BASE_URL || "/api").replace(/\/$/, "");

async function request<T>(path: string, body?: object): Promise<T> {
  const token = sessionToken();
  if (!token) throw new ApiError("请先登录", 401);
  let response: Response;
  try {
    response = await fetch(`${apiBase}/chats/groups${path}`, {
      method: body ? "POST" : "GET",
      headers: { Authorization: `Bearer ${token}`, ...(body ? { "Content-Type": "application/json" } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });
  } catch {
    throw new ApiError("无法连接消息服务，请确认 8082 端口已启动", 0);
  }
  if (!response.ok) {
    const payload = await response.json().catch(() => null);
    throw new ApiError(payload && typeof payload.message === "string"
      ? payload.message : `群聊请求失败（HTTP ${response.status}）`, response.status);
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>;
}

export const listGroups = (beforeId?: number) =>
  request<GroupsPage>(`?limit=50${beforeId ? `&beforeId=${beforeId}` : ""}`);
export const getGroup = (id: number) => request<GroupChat>(`/${id}`);
export const createGroupChat = (name: string, friendIds: number[]) =>
  request<GroupChat>("", { name, friendIds });
export const inviteGroupMembers = (id: number, friendIds: number[]) =>
  request<GroupChat>(`/${id}/members`, { friendIds });
export const loadGroupMessages = (id: number, beforeId?: number) =>
  request<GroupPage>(`/${id}/messages?limit=50${beforeId ? `&beforeId=${beforeId}` : ""}`);
export const syncGroupMessages = (id: number, afterId: number) =>
  request<GroupSyncPage>(`/${id}/messages/sync?afterId=${afterId}&limit=50`);
export const searchGroupMessages = (id: number, query: string, beforeId?: number) =>
  request<GroupPage>(`/${id}/messages/search?q=${encodeURIComponent(query)}&limit=20${beforeId ? `&beforeId=${beforeId}` : ""}`);
export const getGroupMessage = (id: number, messageId: number) =>
  request<GroupMessage>(`/${id}/messages/${messageId}`);
export const sendGroupMessage = (id: number, clientMsgId: string, body: string, mentionedUserIds: number[] = []) =>
  request<GroupMessage>(`/${id}/messages`, { clientMsgId, body, mentionedUserIds });
export const markGroupRead = (id: number, lastReadMessageId: number) =>
  request<void>(`/${id}/read`, { lastReadMessageId });
export const listGroupSummaries = (id: number) =>
  request<GroupSummary[]>(`/${id}/summaries`);
export const generateGroupSummary = (id: number) =>
  request<GroupSummary>(`/${id}/summaries`, {});
