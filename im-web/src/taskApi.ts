import { ApiError, sessionToken } from "./authApi";

export type PersonalTask = {
  id: number;
  title: string;
  completed: boolean;
  createdAt: string;
};
export type TaskPage = {
  items: PersonalTask[];
  nextBeforeId: number | null;
  openCount: number;
  completedCount: number;
};

const apiBase = (import.meta.env?.VITE_API_BASE_URL || "/api").replace(/\/$/, "");

async function request<T>(path: string, method = "GET", body?: object): Promise<T> {
  const token = sessionToken();
  if (!token) throw new ApiError("请先登录", 401);
  let response: Response;
  try {
    response = await fetch(`${apiBase}/chats/tasks${path}`, {
      method,
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
    throw new ApiError(payload && typeof payload.message === "string"
      ? payload.message : `待办请求失败（HTTP ${response.status}）`, response.status);
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>;
}

export const listTasks = (beforeId?: number) =>
  request<TaskPage>(`?limit=50${beforeId ? `&beforeId=${beforeId}` : ""}`);
export const createTask = (title: string) => request<PersonalTask>("", "POST", { title });
export const completeTask = (id: number, completed: boolean) =>
  request<PersonalTask>(`/${id}`, "PATCH", { completed });
export const deleteTask = (id: number) => request<void>(`/${id}`, "DELETE");
