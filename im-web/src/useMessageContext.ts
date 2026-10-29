import { nextTick, ref } from "vue";
import { ApiError } from "./apiClient";
import { loadDirectMessages, syncDirectMessages, type DirectMessage } from "./chatApi";
import { loadGroupMessages, syncGroupMessages, type GroupMessage } from "./groupApi";
import type { ConversationScope } from "./useHistorySearch";

export type ContextMessage = DirectMessage | GroupMessage;

type Deps = {
  accountId: () => number | null;
  scope: () => ConversationScope;
  /** Highlight a message in the transcript, or clear the highlight with null. */
  focusOn: (message: ContextMessage | null) => void;
  /** Leave the excerpt and jump back to the newest messages. */
  showLatest: () => void;
  /** Close the views that the excerpt replaces. */
  beforeOpen: () => void;
  /** Recalls arrive out of band, so the overlay decides whether a body is still readable. */
  recalledAt: (message: ContextMessage) => string | null | undefined;
  scrollEl: () => HTMLElement | null;
  notify: (message: string) => void;
};

/** A read-only window around one search hit, paging in both directions. */
export function useMessageContext(deps: Deps) {
  const active = ref(false);
  const target = ref<ContextMessage | null>(null);
  const messages = ref<ContextMessage[]>([]);
  const beforeId = ref<number | null>(null);
  const afterId = ref<number | null>(null);
  const busy = ref(false);
  const error = ref("");
  let sequence = 0;

  function merge(incoming: ContextMessage[]) {
    const byId = new Map(messages.value.map((message) => [message.id, message]));
    for (const message of incoming) {
      const recalledAt = deps.recalledAt(message) || message.recalledAt;
      byId.set(message.id, recalledAt ? { ...message, body: "", recalledAt } : message);
    }
    messages.value = [...byId.values()].sort((a, b) => a.id - b.id);
  }

  /** A 403 means the membership is gone; close instead of showing a dead excerpt. */
  function fail(cause: unknown, requested: number, fallback: string) {
    if (requested !== sequence) return;
    if (cause instanceof ApiError && cause.status === 403) {
      close(false);
      deps.notify("已无权查看此讨论室");
      return;
    }
    error.value = cause instanceof Error ? cause.message : fallback;
  }

  async function open(message: ContextMessage | null) {
    const account = deps.accountId();
    const { peerId, groupId, conversationId } = deps.scope();
    if (!message || !account || (!peerId && !groupId)) return;
    deps.beforeOpen();
    const requested = ++sequence;
    active.value = true;
    target.value = message;
    messages.value = [];
    beforeId.value = null;
    afterId.value = null;
    busy.value = true;
    error.value = "";
    try {
      const [older, newer] = await Promise.all([
        peerId ? loadDirectMessages(peerId, message.id, 20) : loadGroupMessages(groupId!, message.id, 20),
        peerId ? syncDirectMessages(peerId, message.id, 20) : syncGroupMessages(groupId!, message.id, 20),
      ]);
      if (requested !== sequence || deps.accountId() !== account ||
          deps.scope().conversationId !== conversationId) return;
      merge([...older.messages, message, ...newer.messages]);
      beforeId.value = older.nextBeforeId;
      afterId.value = newer.nextAfterId;
      deps.focusOn(message);
      await nextTick();
      const timer = setTimeout(() => {
        if (requested === sequence) deps.focusOn(null);
      }, 3000);
      // Browsers return a number; Node returns a handle that would keep a test run alive.
      (timer as unknown as { unref?: () => void }).unref?.();
    } catch (cause) {
      fail(cause, requested, "无法查看消息上下文");
    } finally {
      if (requested === sequence) busy.value = false;
    }
  }

  async function load(direction: "older" | "newer") {
    const cursor = direction === "older" ? beforeId.value : afterId.value;
    const account = deps.accountId();
    const { peerId, groupId, conversationId } = deps.scope();
    if (!active.value || !cursor || !account || (!peerId && !groupId) || busy.value) return;
    const requested = ++sequence;
    busy.value = true;
    error.value = "";
    const element = deps.scrollEl();
    const previousHeight = element?.scrollHeight ?? 0;
    const previousTop = element?.scrollTop ?? 0;
    try {
      if (direction === "older") {
        const page = peerId
          ? await loadDirectMessages(peerId, cursor, 20)
          : await loadGroupMessages(groupId!, cursor, 20);
        if (requested !== sequence || deps.accountId() !== account ||
            deps.scope().conversationId !== conversationId || !active.value) return;
        merge(page.messages);
        beforeId.value = page.nextBeforeId;
        await nextTick();
        // Keep the message the user was reading under the cursor.
        const scrolled = deps.scrollEl();
        if (scrolled) scrolled.scrollTop = previousTop + scrolled.scrollHeight - previousHeight;
      } else {
        const page = peerId
          ? await syncDirectMessages(peerId, cursor, 20)
          : await syncGroupMessages(groupId!, cursor, 20);
        if (requested !== sequence || deps.accountId() !== account ||
            deps.scope().conversationId !== conversationId || !active.value) return;
        merge(page.messages);
        afterId.value = page.nextAfterId;
      }
    } catch (cause) {
      fail(cause, requested, "加载上下文失败");
    } finally {
      if (requested === sequence) busy.value = false;
    }
  }

  function close(showLatest = true) {
    sequence++;
    active.value = false;
    target.value = null;
    messages.value = [];
    beforeId.value = null;
    afterId.value = null;
    busy.value = false;
    error.value = "";
    deps.focusOn(null);
    if (showLatest) deps.showLatest();
  }

  return { active, target, messages, beforeId, afterId, busy, error, open, load, close };
}
