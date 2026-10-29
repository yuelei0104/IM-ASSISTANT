import { ref } from "vue";
import {
  listMentionNotices, markAllMentionsRead, markMentionRead,
  getAlertKeywords, saveAlertKeywords, type MentionNotice,
} from "./noticeApi";

type Deps = {
  accountId: () => number | null;
  focusWords: () => string;
  setFocusWords: (value: string) => void;
  notify: (message: string) => void;
};

/** @-mention notices and the alert keywords that generate them. */
export function useNotices(deps: Deps) {
  const items = ref<MentionNotice[]>([]);
  const nextBeforeId = ref<number | null>(null);
  const unreadCount = ref(0);
  const error = ref("");
  const keywordBusy = ref(false);
  const keywordError = ref("");
  const keywordSaved = ref(false);
  let sequence = 0;
  let keywordSequence = 0;

  function merge(incoming: MentionNotice[], keep: (notice: MentionNotice) => boolean) {
    const byId = new Map(items.value.filter(keep).map((notice) => [notice.id, notice]));
    for (const notice of incoming) byId.set(notice.id, notice);
    items.value = [...byId.values()].sort((a, b) => b.id - a.id);
  }

  async function refresh() {
    const account = deps.accountId();
    if (!account) return;
    const requested = ++sequence;
    try {
      const result = await listMentionNotices();
      if (requested !== sequence || deps.accountId() !== account) return;
      merge(result.items, (notice) => result.nextBeforeId !== null && notice.id < result.nextBeforeId);
      nextBeforeId.value = result.nextBeforeId;
      unreadCount.value = result.unreadCount;
      error.value = "";
    } catch (cause) {
      if (requested === sequence)
        error.value = cause instanceof Error ? cause.message : "通知加载失败";
    }
  }

  async function loadOlder() {
    const cursor = nextBeforeId.value;
    const account = deps.accountId();
    if (!cursor || !account) return;
    const requested = ++sequence;
    try {
      const result = await listMentionNotices(cursor);
      if (requested !== sequence || deps.accountId() !== account) return;
      merge(result.items, () => true);
      nextBeforeId.value = result.nextBeforeId;
      unreadCount.value = result.unreadCount;
      error.value = "";
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : "加载历史通知失败";
    }
  }

  async function loadFocusWords() {
    const account = deps.accountId();
    if (!account) return;
    const requested = ++keywordSequence;
    keywordBusy.value = true;
    keywordError.value = "";
    try {
      const saved = await getAlertKeywords();
      if (requested !== keywordSequence || deps.accountId() !== account) return;
      deps.setFocusWords(saved.words.join("，"));
    } catch (cause) {
      if (requested === keywordSequence)
        keywordError.value = cause instanceof Error ? cause.message : "关注词加载失败";
    } finally {
      if (requested === keywordSequence) keywordBusy.value = false;
    }
  }

  async function saveFocusWords() {
    const account = deps.accountId();
    if (!account || keywordBusy.value) return;
    const words = deps.focusWords().split(/[,，]/).map((word) => word.trim()).filter(Boolean);
    const requested = ++keywordSequence;
    keywordBusy.value = true;
    keywordError.value = "";
    keywordSaved.value = false;
    try {
      const saved = await saveAlertKeywords(words);
      if (requested !== keywordSequence || deps.accountId() !== account) return;
      deps.setFocusWords(saved.words.join("，"));
      keywordSaved.value = true;
      deps.notify("关注词已保存，后续群消息命中时会提醒你");
    } catch (cause) {
      if (requested === keywordSequence)
        keywordError.value = cause instanceof Error ? cause.message : "关注词保存失败";
    } finally {
      if (requested === keywordSequence) keywordBusy.value = false;
    }
  }

  /** Mark one notice read; navigation to its message stays with the caller. */
  async function readOne(notice: MentionNotice) {
    if (notice.readAt) return;
    await markMentionRead(notice.id);
    notice.readAt = new Date().toISOString();
    unreadCount.value = Math.max(0, unreadCount.value - 1);
  }

  async function readAll() {
    try {
      await markAllMentionsRead();
      items.value = items.value.map((notice) => ({
        ...notice, readAt: notice.readAt || new Date().toISOString(),
      }));
      unreadCount.value = 0;
      void refresh();
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : "标记失败";
    }
  }

  /** Drop the list without clearing keywords, for a dissolution or membership change. */
  function discard() {
    sequence++;
    items.value = [];
    nextBeforeId.value = null;
  }

  function reset() {
    sequence++;
    items.value = [];
    nextBeforeId.value = null;
    unreadCount.value = 0;
    error.value = "";
    keywordSequence++;
    keywordBusy.value = false;
    keywordError.value = "";
    keywordSaved.value = false;
  }

  return {
    items, nextBeforeId, unreadCount, error, keywordBusy, keywordError, keywordSaved,
    refresh, loadOlder, loadFocusWords, saveFocusWords, readOne, readAll, discard, reset,
  };
}
