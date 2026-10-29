import { ref } from "vue";
import {
  listTasks, createTask, updateTaskDetails, completeTask, deleteTask,
  type PersonalTask, type TaskSource,
} from "./taskApi";
import type { Message } from "./demo";

type Deps = {
  accountId: () => number | null;
  /** Group of the conversation currently open, used when staging a task from a message. */
  activeGroupId: () => number | null;
  openTaskPage: () => void;
  notify: (message: string) => void;
  /** Navigate to a task's source message; throws when it is gone or unreadable. */
  revealSource: (groupId: number, messageId: number) => Promise<void>;
};

/** Server-backed personal tasks, including the review-before-create flow. */
export function useTasks(deps: Deps) {
  const items = ref<PersonalTask[]>([]);
  const nextBeforeId = ref<number | null>(null);
  const openCount = ref(0);
  const completedCount = ref(0);
  const loading = ref(false);
  const saving = ref(false);
  const error = ref("");
  const draft = ref("");
  const draftDueDate = ref("");
  const source = ref<TaskSource | null>(null);
  const editingId = ref<number | null>(null);
  const editingTitle = ref("");
  const editingDueDate = ref("");
  let sequence = 0;

  function current(account: number, requested: number): boolean {
    return requested === sequence && deps.accountId() === account;
  }

  async function refresh(beforeId?: number) {
    const account = deps.accountId();
    if (!account || loading.value || saving.value) return;
    const requested = ++sequence;
    loading.value = true;
    error.value = "";
    try {
      const result = await listTasks(beforeId);
      if (!current(account, requested)) return;
      if (beforeId) {
        const existing = new Map(items.value.map((task) => [task.id, task]));
        for (const task of result.items) existing.set(task.id, task);
        items.value = [...existing.values()].sort((a, b) => b.id - a.id);
      } else {
        items.value = result.items;
      }
      nextBeforeId.value = result.nextBeforeId;
      openCount.value = result.openCount;
      completedCount.value = result.completedCount;
    } catch (cause) {
      if (current(account, requested))
        error.value = cause instanceof Error ? cause.message : "待办加载失败";
    } finally {
      if (requested === sequence) loading.value = false;
    }
  }

  /** Stage a draft from a group message; the user still edits and confirms it. */
  function stageFromMessage(message: Message) {
    const groupId = deps.activeGroupId();
    const match = /^group-message-([1-9]\d*)$/.exec(message.id);
    const messageId = match ? Number(match[1]) : 0;
    if (!groupId || !Number.isSafeInteger(messageId) || !messageId || message.recalled) return;
    source.value = { sourceGroupId: groupId, sourceMessageId: messageId };
    draft.value = message.text.trim().slice(0, 100);
    error.value = "";
    deps.openTaskPage();
    deps.notify("请检查并编辑标题，点击添加后才会保存为私人待办");
  }

  async function openSource(task: PersonalTask) {
    if (!task.sourceGroupId || !task.sourceMessageId || !deps.accountId()) return;
    try {
      await deps.revealSource(task.sourceGroupId, task.sourceMessageId);
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : "无法打开来源消息";
      deps.notify(error.value);
      // The server drops references after a recall or a membership change.
      void refresh();
    }
  }

  async function add() {
    const title = draft.value.trim();
    const account = deps.accountId();
    if (!title || title.length > 100 || !account || saving.value) return;
    saving.value = true;
    const requested = ++sequence;
    loading.value = false;
    error.value = "";
    let saved = false;
    try {
      const created = await createTask(title, source.value || undefined, draftDueDate.value || undefined);
      if (!current(account, requested)) return;
      items.value.unshift(created);
      openCount.value++;
      draft.value = "";
      draftDueDate.value = "";
      source.value = null;
      deps.openTaskPage();
      saved = true;
      deps.notify("待办已创建");
    } catch (cause) {
      if (current(account, requested))
        error.value = cause instanceof Error ? cause.message : "待办创建失败";
    } finally {
      if (requested === sequence && deps.accountId() === account) {
        saving.value = false;
        if (saved) void refresh();
      }
    }
  }

  function startEdit(task: PersonalTask) {
    if (saving.value) return;
    editingId.value = task.id;
    editingTitle.value = task.title;
    editingDueDate.value = task.dueDate || "";
    error.value = "";
  }

  async function saveEdit() {
    const task = items.value.find((item) => item.id === editingId.value);
    const account = deps.accountId();
    const title = editingTitle.value.trim();
    if (!account || !task || !title || title.length > 100 || saving.value) return;
    saving.value = true;
    const requested = ++sequence;
    loading.value = false;
    error.value = "";
    try {
      const saved = await updateTaskDetails(task.id, title, editingDueDate.value || null);
      if (!current(account, requested)) return;
      items.value = items.value.map((item) => (item.id === saved.id ? saved : item));
      editingId.value = null;
    } catch (cause) {
      if (current(account, requested))
        error.value = cause instanceof Error ? cause.message : "待办编辑失败";
    } finally {
      if (requested === sequence && deps.accountId() === account) saving.value = false;
    }
  }

  async function toggle(task: PersonalTask) {
    const account = deps.accountId();
    if (!account || saving.value) return;
    saving.value = true;
    const requested = ++sequence;
    loading.value = false;
    error.value = "";
    let saved = false;
    try {
      const updated = await completeTask(task.id, !task.completed);
      if (!current(account, requested)) return;
      items.value = items.value.map((item) => (item.id === updated.id ? updated : item));
      openCount.value += updated.completed ? -1 : 1;
      completedCount.value += updated.completed ? 1 : -1;
      saved = true;
    } catch (cause) {
      if (current(account, requested))
        error.value = cause instanceof Error ? cause.message : "待办更新失败";
    } finally {
      if (requested === sequence && deps.accountId() === account) {
        saving.value = false;
        if (saved) void refresh();
      }
    }
  }

  async function remove(task: PersonalTask) {
    const account = deps.accountId();
    if (!account || saving.value) return;
    saving.value = true;
    const requested = ++sequence;
    loading.value = false;
    error.value = "";
    let saved = false;
    try {
      await deleteTask(task.id);
      if (!current(account, requested)) return;
      items.value = items.value.filter((item) => item.id !== task.id);
      if (task.completed) completedCount.value--;
      else openCount.value--;
      saved = true;
    } catch (cause) {
      if (current(account, requested))
        error.value = cause instanceof Error ? cause.message : "待办删除失败";
    } finally {
      if (requested === sequence && deps.accountId() === account) {
        saving.value = false;
        if (saved) void refresh();
      }
    }
  }

  /** Drop every task binding and discard any in-flight response. */
  function reset() {
    sequence++;
    items.value = [];
    nextBeforeId.value = null;
    openCount.value = 0;
    completedCount.value = 0;
    loading.value = false;
    saving.value = false;
    error.value = "";
    source.value = null;
    draftDueDate.value = "";
    editingId.value = null;
  }

  return {
    items, nextBeforeId, openCount, completedCount, loading, saving, error,
    draft, draftDueDate, source, editingId, editingTitle, editingDueDate,
    refresh, stageFromMessage, openSource, add, startEdit, saveEdit, toggle, remove, reset,
  };
}
