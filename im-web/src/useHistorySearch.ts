import { ref } from "vue";
import { searchDirectMessages } from "./chatApi";
import { searchGroupMessages, type GroupMessage } from "./groupApi";
import type { DirectMessage } from "./chatApi";

export type ConversationScope = {
  peerId: number | null;
  groupId: number | null;
  conversationId: string;
};

type Deps = {
  accountId: () => number | null;
  /** The conversation on screen, resolved to either a peer or a group id. */
  scope: () => ConversationScope;
};

/** Keyword search inside the open conversation, with paging over older hits. */
export function useHistorySearch(deps: Deps) {
  const open = ref(false);
  const query = ref("");
  const term = ref("");
  const results = ref<Array<DirectMessage | GroupMessage>>([]);
  const nextBeforeId = ref<number | null>(null);
  const searching = ref(false);
  const error = ref("");
  let sequence = 0;

  async function search(beforeId?: number) {
    const keyword = beforeId ? term.value : query.value.trim();
    if (!beforeId && (keyword.length < 2 || keyword.length > 50)) {
      error.value = "请输入 2 到 50 个字符的关键词";
      return;
    }
    const account = deps.accountId();
    if (!keyword || searching.value || !account) return;
    const { peerId, groupId, conversationId } = deps.scope();
    if (!peerId && !groupId) return;
    const requested = ++sequence;
    searching.value = true;
    error.value = "";
    if (!beforeId) {
      term.value = keyword;
      results.value = [];
      nextBeforeId.value = null;
    }
    // The panel may close or the conversation may change while this is in flight.
    const current = () =>
      requested === sequence && deps.accountId() === account &&
      deps.scope().conversationId === conversationId && open.value;
    try {
      const page = peerId
        ? await searchDirectMessages(peerId, keyword, beforeId)
        : await searchGroupMessages(groupId!, keyword, beforeId);
      if (!current()) return;
      results.value = beforeId ? [...results.value, ...page.messages] : page.messages;
      nextBeforeId.value = page.nextBeforeId;
    } catch (cause) {
      if (current()) error.value = cause instanceof Error ? cause.message : "搜索失败，请重试";
    } finally {
      if (requested === sequence) searching.value = false;
    }
  }

  function close() {
    sequence++;
    open.value = false;
    query.value = "";
    term.value = "";
    results.value = [];
    nextBeforeId.value = null;
    searching.value = false;
    error.value = "";
  }

  return { open, query, term, results, nextBeforeId, searching, error, search, close };
}
