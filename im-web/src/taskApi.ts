import { requestJson } from "./apiClient";

export type PersonalTask = {
  id: number;
  title: string;
  completed: boolean;
  createdAt: string;
  sourceGroupId: number | null;
  sourceMessageId: number | null;
  dueDate: string | null;
};
export type TaskPage = {
  items: PersonalTask[];
  nextBeforeId: number | null;
  openCount: number;
  completedCount: number;
};

const apiBase = (import.meta.env?.VITE_API_BASE_URL || "/api").replace(/\/$/, "");

async function request<T>(path: string, method = "GET", body?: object): Promise<T> {
  return requestJson<T>(`${apiBase}/chats/tasks${path}`, {
    method, body, fallback: (status) => `待办请求失败（HTTP ${status}）`,
  });
}

export const listTasks = (beforeId?: number) =>
  request<TaskPage>(`?limit=50${beforeId ? `&beforeId=${beforeId}` : ""}`);
export type TaskSource = { sourceGroupId: number; sourceMessageId: number };
export const createTask = (title: string, source?: TaskSource, dueDate?: string) =>
  request<PersonalTask>("", "POST", { title, ...(source || {}), ...(dueDate ? { dueDate } : {}) });
export const updateTaskDetails = (id: number, title: string, dueDate: string | null) =>
  request<PersonalTask>(`/${id}/details`, "PUT", { title, dueDate });
export const completeTask = (id: number, completed: boolean) =>
  request<PersonalTask>(`/${id}`, "PATCH", { completed });
export const deleteTask = (id: number) => request<void>(`/${id}`, "DELETE");
