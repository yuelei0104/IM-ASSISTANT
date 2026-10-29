import { nextTick, ref } from "vue";
import {
  clearDirectConversation,
  getDirectReadReceipt,
  listDirectRecalls,
  listHiddenDirectConversations,
  listUnreadDirect,
  loadDirectMessages,
  markDirectRead,
  syncDirectMessages,
  type DirectMessage,
  type MessageRecall,
} from "./chatApi";
import {
  getGroup,
  listGroupRecalls,
  listGroups,
  loadGroupMessages,
  markGroupRead,
  syncGroupMessages,
  type GroupChat,
  type GroupMessage,
} from "./groupApi";
import { ApiError } from "./authApi";
import { directPeer, serverGroupId } from "./chatIds";
import { syncForward } from "./incrementalSync";
import { resumeCursor, savedCursor, saveCursor } from "./syncCursor";

type Deps = {
  accountId: () => number | null;
  activeId: () => string;
  page: () => string;
  contextActive: () => boolean;
  notify: (message: string) => void;
  scrollBottom: () => void;
  /** Id of the message the transcript is currently pinning, if any. */
  focusId: () => string;
  /** A confirmed friend never needs a profile lookup again. */
  hasPeer: (peerId: number) => boolean;
  /** Resolve an unknown unread sender into the friend cache; failures stay invisible. */
  rememberPeer: (peerId: number) => Promise<void>;
  /** A recall reached the thread, so search hits and excerpts must redact the same body. */
  recalledInView: (recall: MessageRecall, kind: "direct" | "group") => void;
  /** A group recall invalidates the cached summary and any translation of that message. */
  groupRecallApplied: (messageId: number) => void;
  /** New group recalls arrived, so a visible summary has to be fetched again. */
  groupRecallsSynced: () => void;
  /** Membership is gone: the shell drops the room's views and derived data. */
  groupRemoved: (groupId: number) => void;
  /** The user deleted a direct conversation; the shell clears the selection and overlays. */
  conversationDeleted: () => void;
};

/**
 * The sync model for the transcripts: paged history, durable cursors, recalls, read
 * receipts, unread counters and the room list. Request sequencing (`chatSeq`) lives here
 * so a late response can never overwrite the conversation the user is looking at now.
 */
export function useChatFeed(deps: Deps) {
  const directMessages = ref<DirectMessage[]>([]);
  const peerLastReadId = ref(0);
  const directSyncCursor = ref<number | null>(null);
  const nextBeforeId = ref<number | null>(null);
  const groupMessages = ref<GroupMessage[]>([]);
  const groupSyncCursor = ref<number | null>(null);
  const groupNextBeforeId = ref<number | null>(null);
  const directRecallCursor = ref(0);
  const groupRecallCursor = ref(0);
  const serverGroups = ref<GroupChat[]>([]);
  const groupsNextCursor = ref<number | null>(null);
  const directUnread = ref<Record<number, number>>({});
  const hiddenPeers = ref<number[]>([]);
  const chatBusy = ref(false);
  const chatError = ref("");
  const directRecalled = new Map<number, string>();
  const groupRecalled = new Map<number, string>();
  const lastMarkedRead = new Map<number, number>();
  const readInFlight = new Set<number>();
  const groupLastRead = new Map<number, number>();
  const groupReadInFlight = new Set<number>();
  const loadingPeers = new Set<number>();
  let chatSeq = 0;
  let groupsSeq = 0;
  let unreadSeq = 0;
  let unreadBusy = false;
  let hiddenSeq = 0;
  let pendingRealtimeRefresh = false;
  let pendingGroupRefresh = false;

  function mergeDirect(incoming: DirectMessage[]) {
    const byId = new Map(directMessages.value.map((message) => [message.id, message]));
    for (const message of incoming) {
      const recalledAt = directRecalled.get(message.id) || message.recalledAt;
      byId.set(message.id, recalledAt ? { ...message, body: "", recalledAt } : message);
    }
    directMessages.value = [...byId.values()].sort((a, b) => a.id - b.id);
  }

  function mergeGroup(incoming: GroupMessage[]) {
    const byId = new Map(groupMessages.value.map((message) => [message.id, message]));
    for (const message of incoming) {
      const recalledAt = groupRecalled.get(message.id) || message.recalledAt;
      byId.set(message.id, recalledAt ? { ...message, body: "", recalledAt } : message);
    }
    groupMessages.value = [...byId.values()].sort((a, b) => a.id - b.id);
  }

  /** Recalls arrive out of band; an excerpt decides from this whether a body is still readable. */
  function recalledAt(message: DirectMessage | GroupMessage): string | null | undefined {
    return ("groupId" in message ? groupRecalled : directRecalled).get(message.id) ||
      message.recalledAt;
  }

  function applyDirectRecall(recall: MessageRecall) {
    directRecalled.set(recall.messageId, recall.recalledAt);
    directMessages.value = directMessages.value.map((message) => message.id === recall.messageId
      ? { ...message, body: "", recalledAt: recall.recalledAt } : message);
    deps.recalledInView(recall, "direct");
  }

  function applyGroupRecall(recall: MessageRecall) {
    groupRecalled.set(recall.messageId, recall.recalledAt);
    groupMessages.value = groupMessages.value.map((message) => message.id === recall.messageId
      ? { ...message, body: "", recalledAt: recall.recalledAt } : message);
    deps.recalledInView(recall, "group");
    deps.groupRecallApplied(recall.messageId);
  }

  async function syncDirectRecalls(peerId: number, accountId: number, sequence: number) {
    await syncForward(directRecallCursor.value,
      async (cursor) => {
        const page = await listDirectRecalls(peerId, cursor);
        return { messages: page.items, nextAfterId: page.nextAfterId };
      }, (items, cursor) => {
        if (sequence !== chatSeq || deps.accountId() !== accountId ||
            directPeer(deps.activeId()) !== peerId) return false;
        items.forEach(applyDirectRecall);
        directRecallCursor.value = cursor;
        return true;
      });
  }

  async function syncGroupRecalls(groupId: number, accountId: number, sequence: number) {
    let changed = false;
    await syncForward(groupRecallCursor.value,
      async (cursor) => {
        const page = await listGroupRecalls(groupId, cursor);
        return { messages: page.items, nextAfterId: page.nextAfterId };
      }, (items, cursor) => {
        if (sequence !== chatSeq || deps.accountId() !== accountId ||
            serverGroupId(deps.activeId()) !== groupId) return false;
        changed = changed || items.length > 0;
        items.forEach(applyGroupRecall);
        groupRecallCursor.value = cursor;
        return true;
      });
    if (changed && sequence === chatSeq) deps.groupRecallsSynced();
  }

  async function refreshUnread() {
    const accountId = deps.accountId();
    if (!accountId || unreadBusy) return;
    unreadBusy = true;
    const sequence = ++unreadSeq;
    try {
      const counts = await listUnreadDirect();
      if (sequence !== unreadSeq || deps.accountId() !== accountId) return;
      directUnread.value = Object.fromEntries(
        counts.map(({ peerId, unreadCount }) => [peerId, unreadCount]));
      for (const { peerId, unreadCount } of counts) {
        if (unreadCount > 0 && !deps.hasPeer(peerId) && !loadingPeers.has(peerId))
          void loadUnreadPeer(peerId, accountId);
      }
    } catch {
      // Keep the last confirmed counts; a temporary outage must not mark messages as read.
    } finally {
      if (sequence === unreadSeq) unreadBusy = false;
    }
  }

  async function loadUnreadPeer(peerId: number, accountId: number) {
    loadingPeers.add(peerId);
    try {
      await deps.rememberPeer(peerId);
      if (deps.accountId() !== accountId) return;
    } catch {
      // An old sender may no longer be a friend. Do not reveal the profile.
    } finally {
      loadingPeers.delete(peerId);
    }
  }

  async function markVisibleRead(peerId: number) {
    const accountId = deps.accountId();
    if (!accountId || deps.contextActive() || deps.page() !== "chat" ||
        directPeer(deps.activeId()) !== peerId || document.hidden || readInFlight.has(peerId)) return;
    const lastReceivedId = directMessages.value
      .filter((message) => message.senderId === peerId && message.recipientId === accountId).at(-1)?.id;
    if (!lastReceivedId || (lastMarkedRead.get(peerId) ?? 0) >= lastReceivedId) return;
    readInFlight.add(peerId);
    try {
      await markDirectRead(peerId, lastReceivedId);
      if (deps.accountId() !== accountId) return;
      lastMarkedRead.set(peerId, lastReceivedId);
      unreadSeq++;
      unreadBusy = false;
      void refreshUnread();
    } catch {
      // Try again at the next refresh; do not discard unread state on failure.
    } finally {
      readInFlight.delete(peerId);
    }
  }

  async function refreshPeerReadReceipt(peerId: number, accountId: number, sequence: number) {
    try {
      const receipt = await getDirectReadReceipt(peerId);
      if (sequence !== chatSeq || deps.accountId() !== accountId ||
          directPeer(deps.activeId()) !== peerId) return;
      peerLastReadId.value = Math.max(peerLastReadId.value, receipt.lastReadMessageId);
    } catch {
      // A later notification, reconnect or recovery poll can fetch the durable read cursor.
    }
  }

  async function refreshDirectChat() {
    const peerId = directPeer(deps.activeId());
    const accountId = deps.accountId();
    if (!peerId || !accountId || chatBusy.value) return;
    const sequence = ++chatSeq;
    chatBusy.value = true;
    try {
      if (directSyncCursor.value === null) {
        const result = await loadDirectMessages(peerId);
        if (sequence !== chatSeq || deps.accountId() !== accountId ||
            directPeer(deps.activeId()) !== peerId) return;
        nextBeforeId.value = result.nextBeforeId;
        mergeDirect(result.messages);
        const newest = result.messages.at(-1)?.id ?? 0;
        directSyncCursor.value = resumeCursor(savedCursor(accountId, "direct", peerId), newest);
        saveCursor(accountId, "direct", peerId, directSyncCursor.value);
      }
      const resumed = await syncForward(directSyncCursor.value,
        (cursor) => syncDirectMessages(peerId, cursor), (messages, cursor) => {
          if (sequence !== chatSeq || deps.accountId() !== accountId ||
              directPeer(deps.activeId()) !== peerId) return false;
          mergeDirect(messages);
          directSyncCursor.value = cursor;
          saveCursor(accountId, "direct", peerId, cursor);
          return true;
        });
      if (resumed === null) return;
      await syncDirectRecalls(peerId, accountId, sequence);
      void refreshPeerReadReceipt(peerId, accountId, sequence);
      chatError.value = "";
      await nextTick();
      deps.scrollBottom();
      void markVisibleRead(peerId);
    } catch (cause) {
      if (sequence === chatSeq)
        chatError.value = cause instanceof Error ? cause.message : "消息加载失败";
    } finally {
      if (sequence === chatSeq) chatBusy.value = false;
      if (pendingRealtimeRefresh && sequence === chatSeq) {
        pendingRealtimeRefresh = false;
        void refreshDirectChat();
      }
    }
  }

  async function loadOlderDirect() {
    const peerId = directPeer(deps.activeId());
    const beforeId = nextBeforeId.value;
    const accountId = deps.accountId();
    if (!peerId || !beforeId || !accountId || chatBusy.value) return;
    const sequence = ++chatSeq;
    chatBusy.value = true;
    try {
      const result = await loadDirectMessages(peerId, beforeId);
      if (sequence !== chatSeq || deps.accountId() !== accountId ||
          directPeer(deps.activeId()) !== peerId) return;
      mergeDirect(result.messages);
      nextBeforeId.value = result.nextBeforeId;
      chatError.value = "";
    } catch (cause) {
      if (sequence === chatSeq)
        chatError.value = cause instanceof Error ? cause.message : "加载历史失败";
    } finally {
      if (sequence === chatSeq) chatBusy.value = false;
      if (pendingRealtimeRefresh && sequence === chatSeq) {
        pendingRealtimeRefresh = false;
        void refreshDirectChat();
      }
    }
  }

  async function refreshGroups() {
    const accountId = deps.accountId();
    if (!accountId) return;
    const sequence = ++groupsSeq;
    try {
      const result = await listGroups();
      if (sequence !== groupsSeq || deps.accountId() !== accountId) return;
      // The newest page is authoritative; retain only older pages the user already loaded.
      const byId = new Map(serverGroups.value
        .filter((group) => result.nextBeforeId !== null && group.id < result.nextBeforeId)
        .map((group) => [group.id, group]));
      for (const group of result.items) byId.set(group.id, group);
      serverGroups.value = [...byId.values()].sort((a, b) => b.id - a.id);
      groupsNextCursor.value = result.nextBeforeId;
    } catch (cause) {
      if (sequence === groupsSeq && !serverGroups.value.length)
        chatError.value = cause instanceof Error ? cause.message : "讨论室加载失败";
    }
  }

  async function loadMoreGroups() {
    const cursor = groupsNextCursor.value;
    const accountId = deps.accountId();
    if (!cursor || !accountId) return;
    const sequence = ++groupsSeq;
    try {
      const result = await listGroups(cursor);
      if (sequence !== groupsSeq || deps.accountId() !== accountId) return;
      const byId = new Map(serverGroups.value.map((group) => [group.id, group]));
      for (const group of result.items) byId.set(group.id, group);
      serverGroups.value = [...byId.values()];
      groupsNextCursor.value = result.nextBeforeId;
    } catch (cause) {
      deps.notify(cause instanceof Error ? cause.message : "讨论室加载失败");
    }
  }

  function forgetGroup(groupId: number) {
    groupsSeq++; // Ignore a group list that was fetched before membership changed.
    serverGroups.value = serverGroups.value.filter((group) => group.id !== groupId);
    if (serverGroupId(deps.activeId()) === groupId) {
      chatSeq++;
      groupMessages.value = [];
      groupRecallCursor.value = 0;
      groupRecalled.clear();
      groupSyncCursor.value = null;
      groupNextBeforeId.value = null;
      pendingGroupRefresh = false;
      chatBusy.value = false;
      chatError.value = "";
    }
    groupLastRead.delete(groupId);
    groupReadInFlight.delete(groupId);
    deps.groupRemoved(groupId);
  }

  async function reconcileGroupMembership(groupId: number) {
    const accountId = deps.accountId();
    if (!accountId) return;
    try {
      const group = await getGroup(groupId);
      if (deps.accountId() !== accountId) return;
      serverGroups.value = [group, ...serverGroups.value.filter((item) => item.id !== groupId)]
        .sort((a, b) => b.id - a.id);
    } catch (cause) {
      if (deps.accountId() === accountId && cause instanceof ApiError && cause.status === 403)
        forgetGroup(groupId);
    }
  }

  async function markVisibleGroupRead(groupId: number) {
    const accountId = deps.accountId();
    if (!accountId || deps.contextActive() || document.hidden || deps.page() !== "chat" ||
        serverGroupId(deps.activeId()) !== groupId || groupReadInFlight.has(groupId)) return;
    const last = groupMessages.value.at(-1)?.id;
    if (!last || (groupLastRead.get(groupId) ?? 0) >= last) return;
    groupReadInFlight.add(groupId);
    try {
      await markGroupRead(groupId, last);
      if (deps.accountId() !== accountId) return;
      groupLastRead.set(groupId, last);
      const group = serverGroups.value.find((item) => item.id === groupId);
      if (group) group.unreadCount = 0;
      void refreshGroups();
    } catch {
      // A later sync retries without clearing the count.
    } finally {
      groupReadInFlight.delete(groupId);
    }
  }

  async function refreshGroupChat() {
    const groupId = serverGroupId(deps.activeId());
    const accountId = deps.accountId();
    if (!groupId || !accountId) return;
    if (chatBusy.value) { pendingGroupRefresh = true; return; }
    const sequence = ++chatSeq;
    chatBusy.value = true;
    try {
      if (groupSyncCursor.value === null) {
        const result = await loadGroupMessages(groupId);
        if (sequence !== chatSeq || deps.accountId() !== accountId ||
            serverGroupId(deps.activeId()) !== groupId) return;
        groupNextBeforeId.value = result.nextBeforeId;
        mergeGroup(result.messages);
        const newest = result.messages.at(-1)?.id ?? 0;
        groupSyncCursor.value = resumeCursor(savedCursor(accountId, "group", groupId), newest);
        saveCursor(accountId, "group", groupId, groupSyncCursor.value);
      }
      const resumed = await syncForward(groupSyncCursor.value,
        (cursor) => syncGroupMessages(groupId, cursor), (messages, cursor) => {
          if (sequence !== chatSeq || deps.accountId() !== accountId ||
              serverGroupId(deps.activeId()) !== groupId) return false;
          mergeGroup(messages);
          groupSyncCursor.value = cursor;
          saveCursor(accountId, "group", groupId, cursor);
          return true;
        });
      if (resumed === null) return;
      await syncGroupRecalls(groupId, accountId, sequence);
      chatError.value = "";
      await nextTick();
      // A background sync must not pull the reader away from the excerpt or the pinned hit.
      if (deps.contextActive()) { /* Keep the context view in place. */ }
      else if (deps.focusId())
        document.getElementById(`msg-${deps.focusId()}`)
          ?.scrollIntoView({ behavior: "smooth", block: "center" });
      else deps.scrollBottom();
      void markVisibleGroupRead(groupId);
    } catch (cause) {
      if (sequence === chatSeq) {
        if (cause instanceof ApiError && cause.status === 403) forgetGroup(groupId);
        else chatError.value = cause instanceof Error ? cause.message : "群消息加载失败";
      }
    } finally {
      if (sequence === chatSeq) chatBusy.value = false;
      if (pendingGroupRefresh && sequence === chatSeq) {
        pendingGroupRefresh = false;
        void refreshGroupChat();
      }
    }
  }

  async function loadOlderGroup() {
    const groupId = serverGroupId(deps.activeId());
    const cursor = groupNextBeforeId.value;
    const accountId = deps.accountId();
    if (!groupId || !cursor || !accountId || chatBusy.value) return;
    const sequence = ++chatSeq;
    chatBusy.value = true;
    try {
      const result = await loadGroupMessages(groupId, cursor);
      if (sequence !== chatSeq || deps.accountId() !== accountId ||
          serverGroupId(deps.activeId()) !== groupId) return;
      mergeGroup(result.messages);
      groupNextBeforeId.value = result.nextBeforeId;
      chatError.value = "";
    } catch (cause) {
      if (sequence === chatSeq)
        chatError.value = cause instanceof Error ? cause.message : "加载历史失败";
    } finally {
      if (sequence === chatSeq) chatBusy.value = false;
      if (pendingGroupRefresh && sequence === chatSeq) {
        pendingGroupRefresh = false;
        void refreshGroupChat();
      }
    }
  }

  async function refreshHiddenConversations() {
    const accountId = deps.accountId();
    if (!accountId) return;
    const sequence = ++hiddenSeq;
    try {
      const ids = await listHiddenDirectConversations();
      if (sequence === hiddenSeq && deps.accountId() === accountId)
        hiddenPeers.value = ids;
    } catch {
      // The server remains authoritative; retry on the next reconnect or poll.
    }
  }

  async function deleteConversation(peerId: number, name: string) {
    const accountId = deps.accountId();
    if (!accountId) return;
    if (!confirm(`删除与 ${name} 的会话？只清空你可见的旧消息和未读，不删除对方的记录，也不会移除好友。`))
      return;
    try {
      await clearDirectConversation(peerId);
      if (deps.accountId() !== accountId) return;
      hiddenSeq++;
      chatSeq++;
      unreadSeq++;
      unreadBusy = false;
      pendingRealtimeRefresh = false;
      hiddenPeers.value = [...new Set([...hiddenPeers.value, peerId])];
      directMessages.value = [];
      directUnread.value = { ...directUnread.value, [peerId]: 0 };
      deps.conversationDeleted();
      void refreshHiddenConversations();
      void refreshUnread();
      deps.notify("会话已从你的列表移除，好友关系保持不变");
    } catch (cause) {
      deps.notify(cause instanceof Error ? cause.message : "删除会话失败");
    }
  }

  /** Clear every cached thread; used when the account changes or the session ends. */
  function reset() {
    chatSeq++;
    groupsSeq++;
    unreadSeq++;
    hiddenSeq++;
    unreadBusy = false;
    pendingRealtimeRefresh = false;
    pendingGroupRefresh = false;
    directMessages.value = [];
    peerLastReadId.value = 0;
    groupMessages.value = [];
    directRecallCursor.value = 0;
    groupRecallCursor.value = 0;
    directRecalled.clear();
    groupRecalled.clear();
    directSyncCursor.value = null;
    groupSyncCursor.value = null;
    nextBeforeId.value = null;
    groupNextBeforeId.value = null;
    serverGroups.value = [];
    groupsNextCursor.value = null;
    groupLastRead.clear();
    groupReadInFlight.clear();
    directUnread.value = {};
    lastMarkedRead.clear();
    readInFlight.clear();
    loadingPeers.clear();
    hiddenPeers.value = [];
    chatBusy.value = false;
    chatError.value = "";
  }

  /** Drop only the open thread, keeping the room list and unread counters. */
  function resetConversationState() {
    chatSeq++;
    directMessages.value = [];
    peerLastReadId.value = 0;
    groupMessages.value = [];
    directRecallCursor.value = 0;
    groupRecallCursor.value = 0;
    directRecalled.clear();
    groupRecalled.clear();
    directSyncCursor.value = null;
    groupSyncCursor.value = null;
    nextBeforeId.value = null;
    groupNextBeforeId.value = null;
    chatError.value = "";
    chatBusy.value = false;
    pendingRealtimeRefresh = false;
    pendingGroupRefresh = false;
  }

  /** Load whichever thread the shell just opened. */
  function loadActive() {
    if (directPeer(deps.activeId())) void refreshDirectChat();
    if (serverGroupId(deps.activeId())) void refreshGroupChat();
  }

  function refreshAll() {
    void refreshUnread();
    void refreshHiddenConversations();
    void refreshGroups();
  }

  function requestDirectRefresh() {
    if (chatBusy.value) { pendingRealtimeRefresh = true; return; }
    void refreshDirectChat();
  }

  function requestReceipt(peerId: number) {
    const accountId = deps.accountId();
    if (accountId) void refreshPeerReadReceipt(peerId, accountId, chatSeq);
  }

  return {
    directMessages, peerLastReadId, nextBeforeId, groupMessages, groupNextBeforeId,
    serverGroups, groupsNextCursor, directUnread, hiddenPeers, chatBusy, chatError,
    mergeDirect, mergeGroup, recalledAt, applyDirectRecall, applyGroupRecall,
    refreshUnread, markVisibleRead, refreshDirectChat, loadOlderDirect,
    refreshGroups, loadMoreGroups, forgetGroup, reconcileGroupMembership,
    markVisibleGroupRead, refreshGroupChat, loadOlderGroup, refreshHiddenConversations,
    deleteConversation, reset, resetConversationState, loadActive, refreshAll,
    requestDirectRefresh, requestReceipt,
  };
}
