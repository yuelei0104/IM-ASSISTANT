import { requestJson } from "./apiClient";

export type ContactProfile = { id: number; nickname: string; handle: string };
export type ContactPage<T> = { items: T[]; nextCursor: number | null };
export type ContactRequest = {
  id: number;
  fromId: number;
  toId: number;
  peerId: number;
  peerNickname: string;
  peerHandle: string;
  note: string;
  state: "PENDING" | "ACCEPTED" | "REJECTED" | "WITHDRAWN";
  createdAt: string;
};

const apiBase = (import.meta.env?.VITE_API_BASE_URL || "/api").replace(
  /\/$/,
  "",
);

async function request<T>(
  path: string,
  method = "GET",
  body?: object,
): Promise<T> {
  return requestJson<T>(`${apiBase}/contacts${path}`, {
    method, body, emptyStatuses: [201, 202],
    fallback: () => "操作失败，请稍后重试",
  });
}

export const listFriends = (afterId?: number) => request<ContactPage<ContactProfile>>(
  afterId ? `?afterId=${afterId}` : "",
);
export const listRequests = (beforeId?: number) => request<ContactPage<ContactRequest>>(
  beforeId ? `/requests?beforeId=${beforeId}` : "/requests",
);
export const friendProfile = (friendId: number) => request<ContactProfile>(`/${friendId}/profile`);
export const inviteFriend = (
  identifier: { handle: string } | { phone: string },
  note: string,
) => request<void>("/requests", "POST", { ...identifier, note });
export const resolveFriendRequest = (
  id: number,
  action: "accept" | "reject" | "withdraw",
) => request<void>(`/requests/${id}/${action}`, "POST");
export const deleteFriend = (id: number) => request<void>(`/${id}`, "DELETE");
