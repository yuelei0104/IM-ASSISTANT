import { requestJson } from "./apiClient";

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
  kind: "TEXT" | "CAPTION";
  createdAt: string;
  recalledAt: string | null;
};
export type MessageRecall = { id: number; messageId: number; recalledAt: string };
export type RecallPage = { items: MessageRecall[]; nextAfterId: number | null };
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
export type SummarySource = {
  id: number;
  senderId: number;
  body: string;
  kind: "TEXT" | "CAPTION";
  createdAt: string;
};
export type TranslationPreview = { sourceMessageId: number; targetLanguage: "en" | "zh"; text: string };
export type GroupAgentAnswer = { answer: string; sources: { id: number; body: string }[] };
export type GroupsPage = { items: GroupChat[]; nextBeforeId: number | null };
const apiBase = (import.meta.env?.VITE_API_BASE_URL || "/api").replace(/\/$/, "");

async function request<T>(path: string, body?: object, method?: "DELETE"): Promise<T> {
  const modelRequest = path.endsWith("/agent/ask") || path.endsWith("/summaries") && body !== undefined
    || path.endsWith("/translation");
  return requestJson<T>(`${apiBase}/chats/groups${path}`, {
    method: method || (body ? "POST" : "GET"), body,
    timeoutMs: modelRequest ? 180_000 : 15_000,
    fallback: (status) => `群聊请求失败（HTTP ${status}）`,
  });
}

export const listGroups = (beforeId?: number) =>
  request<GroupsPage>(`?limit=50${beforeId ? `&beforeId=${beforeId}` : ""}`);
export const getGroup = (id: number) => request<GroupChat>(`/${id}`);
export const createGroupChat = (name: string, friendIds: number[]) =>
  request<GroupChat>("", { name, friendIds });
export const inviteGroupMembers = (id: number, friendIds: number[]) =>
  request<GroupChat>(`/${id}/members`, { friendIds });
export const leaveGroup = (id: number) =>
  request<void>(`/${id}/members/me`, undefined, "DELETE");
export const removeGroupMember = (id: number, memberId: number) =>
  request<void>(`/${id}/members/${memberId}`, undefined, "DELETE");
export const transferGroupOwner = (id: number, memberId: number) =>
  request<GroupChat>(`/${id}/owner`, { memberId });
export const dissolveGroup = (id: number) =>
  request<void>(`/${id}`, undefined, "DELETE");
export const loadGroupMessages = (id: number, beforeId?: number, limit = 50) =>
  request<GroupPage>(`/${id}/messages?limit=${limit}${beforeId ? `&beforeId=${beforeId}` : ""}`);
export const syncGroupMessages = (id: number, afterId: number, limit = 50) =>
  request<GroupSyncPage>(`/${id}/messages/sync?afterId=${afterId}&limit=${limit}`);
export const searchGroupMessages = (id: number, query: string, beforeId?: number) =>
  request<GroupPage>(`/${id}/messages/search?q=${encodeURIComponent(query)}&limit=20${beforeId ? `&beforeId=${beforeId}` : ""}`);
export const getGroupMessage = (id: number, messageId: number) =>
  request<GroupMessage>(`/${id}/messages/${messageId}`);
export const translateGroupMessage = (id: number, messageId: number, targetLanguage: "en" | "zh") =>
  request<TranslationPreview>(`/${id}/messages/${messageId}/translation`, { targetLanguage });
export const sendGroupMessage = (id: number, clientMsgId: string, body: string, mentionedUserIds: number[] = [], kind: "TEXT" | "CAPTION" = "TEXT") =>
  request<GroupMessage>(`/${id}/messages`, { clientMsgId, body, mentionedUserIds, kind });
export const recallGroupMessage = (id: number, messageId: number) =>
  request<void>(`/${id}/messages/${messageId}`, undefined, "DELETE");
export const listGroupRecalls = (id: number, afterId: number) =>
  request<RecallPage>(`/${id}/messages/recalls?afterId=${afterId}&limit=50`);
export const markGroupRead = (id: number, lastReadMessageId: number) =>
  request<void>(`/${id}/read`, { lastReadMessageId });
export const listGroupSummaries = (id: number) =>
  request<GroupSummary[]>(`/${id}/summaries`);
export const generateGroupSummary = (id: number) =>
  request<GroupSummary>(`/${id}/summaries`, {});
export const askGroupAgent = (id: number, question: string) =>
  request<GroupAgentAnswer>(`/${id}/agent/ask`, { question });
export const listGroupSummarySources = (id: number, summaryId: number) =>
  request<SummarySource[]>(`/${id}/summaries/${summaryId}/sources`);
