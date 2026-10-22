import { ApiError, sessionToken } from "./authApi";

export type ContactProfile = { id: number; nickname: string; handle: string };
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
  const token = sessionToken();
  if (!token) throw new ApiError("请先登录", 401);
  let response: Response;
  try {
    response = await fetch(`${apiBase}/contacts${path}`, {
      method,
      headers: {
        Authorization: `Bearer ${token}`,
        ...(body ? { "Content-Type": "application/json" } : {}),
      },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });
  } catch {
    throw new ApiError("无法连接用户服务，请确认后端已启动", 0);
  }
  if (!response.ok) {
    const payload = await response.json().catch(() => null);
    throw new ApiError(
      payload?.message || "操作失败，请稍后重试",
      response.status,
    );
  }
  if (response.status === 204 || response.status === 201) return undefined as T;
  return response.json() as Promise<T>;
}

export const listFriends = () => request<ContactProfile[]>("");
export const listRequests = () => request<ContactRequest[]>("/requests");
export const findUser = (handle: string) =>
  request<ContactProfile>(`/users/${encodeURIComponent(handle)}`);
export const inviteFriend = (handle: string, note: string) =>
  request<void>("/requests", "POST", { handle, note });
export const resolveFriendRequest = (
  id: number,
  action: "accept" | "reject" | "withdraw",
) => request<void>(`/requests/${id}/${action}`, "POST");
export const deleteFriend = (id: number) => request<void>(`/${id}`, "DELETE");
