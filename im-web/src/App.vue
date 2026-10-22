<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import AppIcon from "./components/AppIcon.vue";
import {
  accountStateKey,
  bindContact as bindAccountContact,
  checkRegistration,
  clearSession,
  currentUser,
  loginWithCode,
  loginWithPassword,
  logout,
  register,
  resetPassword,
  sendCode,
  sessionToken,
  ApiError,
  type Account,
  type CodePurpose,
} from "./authApi";
import {
  normalizeContact,
  validContact,
  validPassword,
  validateRegistration,
  type ContactType,
  type RegistrationInput,
} from "./authValidation";
import {
  deleteFriend,
  friendProfile,
  inviteFriend,
  listFriends,
  listRequests,
  resolveFriendRequest,
  type ContactProfile,
  type ContactRequest,
} from "./contactApi";
import { connectChatSocket, listUnreadDirect, loadDirectMessages, syncDirectMessages, markDirectRead, sendDirectMessage, type DirectMessage } from "./chatApi";
import {
  createGroupChat, getGroupMessage, inviteGroupMembers, listGroups, loadGroupMessages,
  markGroupRead, sendGroupMessage, syncGroupMessages, type GroupChat, type GroupMessage,
} from "./groupApi";
import { listMentionNotices, markAllMentionsRead, markMentionRead, type MentionNotice } from "./noticeApi";
import { syncForward } from "./incrementalSync";
import { resumeCursor, savedCursor, saveCursor } from "./syncCursor";
import {
  initialState,
  selfId,
  uid,
  type Conversation,
  type DemoState,
  type Message,
  type Person,
} from "./demo";

type Page = "chat" | "contacts" | "requests" | "notices" | "tasks" | "settings";
type Modal = "group" | "invite" | "friend" | null;
function load(account: Account | null): DemoState {
  if (!account) return initialState();
  try {
    const saved = localStorage.getItem(accountStateKey(account.id));
    if (saved) {
      const v = JSON.parse(saved);
      if (v.people && v.conversations && v.preferences) return v;
    }
  } catch {}
  return initialState();
}
function peerCacheKey(id: number): string { return `${accountStateKey(id)}-chat-peers`; }
function loadKnownPeers(account: Account): ContactProfile[] {
  try {
    const saved = JSON.parse(localStorage.getItem(peerCacheKey(account.id)) || "[]");
    if (!Array.isArray(saved)) return [];
    return saved.filter((p) =>
      Number.isSafeInteger(p.id) && p.id > 0 &&
      typeof p.nickname === "string" && typeof p.handle === "string",
    );
  } catch { return []; }
}
const currentAccount = ref<Account | null>(null);
const data = ref<DemoState>(load(currentAccount.value));
const loggedIn = computed(() => currentAccount.value !== null);
const authRestoring = ref(Boolean(sessionToken()));
const authMode = ref<"login" | "register" | "reset">("login");
const registerStep = ref<"details" | "verify">("details");
const pendingRegistration = ref<RegistrationInput | null>(null);
const contactType = ref<ContactType>("phone");
const loginMethod = ref<"password" | "code">("password");
const authIdentifier = ref("");
const authPhone = ref("");
const authEmail = ref("");
const authPhoneCode = ref("");
const authEmailCode = ref("");
const authLoginCode = ref("");
const authPassword = ref("");
const authConfirm = ref("");
const authName = ref("");
const authHandle = ref("");
const authError = ref("");
const authSuccess = ref("");
const authBusy = ref(false);
const showPassword = ref(false);
const bindValue = ref("");
const bindPassword = ref("");
const bindCode = ref("");
const bindError = ref("");
const bindSuccess = ref("");
const bindBusy = ref(false);
type CodeSlot = "phone" | "email" | "login" | "bind";
const codeHints = ref<Record<CodeSlot, string>>({
  phone: "",
  email: "",
  login: "",
  bind: "",
});
const codeBusy = ref<Record<CodeSlot, boolean>>({
  phone: false,
  email: false,
  login: false,
  bind: false,
});
const resendAt = ref<Record<string, number>>({});
const codeTick = ref(Date.now());
const codeInterval = setInterval(() => (codeTick.value = Date.now()), 1000);
watch(authPhone, () => {
  authPhoneCode.value = "";
  codeHints.value.phone = "";
});
watch(authEmail, () => {
  authEmailCode.value = "";
  codeHints.value.email = "";
});
watch(authIdentifier, () => {
  authLoginCode.value = "";
  codeHints.value.login = "";
});
watch(bindValue, () => {
  bindCode.value = "";
  codeHints.value.bind = "";
});
const missingContact = computed<ContactType | null>(() =>
  !currentAccount.value?.phone
    ? "phone"
    : !currentAccount.value?.email
      ? "email"
      : null,
);
const page = ref<Page>("chat");
const activeId = ref("");
const search = ref("");
const composer = ref("");
const mentionPickerOpen = ref(false);
const mentionSelections = ref<Record<number, string>>({});
const modal = ref<Modal>(null);
const infoOpen = ref(false);
const assistantOpen = ref(false);
const mobileChatOpen = ref(false);
const unreadMarker = ref("");
const toast = ref("");
const range = ref<"recent" | "all">("recent");
const showSummary = ref(false);
const taskDraft = ref("");
const groupName = ref("");
const inviteIds = ref<string[]>([]);
const friendSearchType = ref<"phone" | "handle">("phone");
const friendQuery = ref("");
const friendNote = ref("");
const friendError = ref("");
const friendBusy = ref(false);
const contactProfiles = ref<ContactProfile[]>([]);
const friendsNextCursor = ref<number | null>(null);
const requestsNextCursor = ref<number | null>(null);
const knownPeers = ref<ContactProfile[]>([]);
const loadingPeers = new Set<number>();
const directMessages = ref<DirectMessage[]>([]);
const directSyncCursor = ref<number | null>(null);
const serverGroups = ref<GroupChat[]>([]);
const groupsNextCursor = ref<number | null>(null);
const groupMessages = ref<GroupMessage[]>([]);
const groupSyncCursor = ref<number | null>(null);
const groupNextBeforeId = ref<number | null>(null);
const groupBusy = ref(false);
let groupsSeq = 0;
const groupReadInFlight = new Set<number>();
const groupLastRead = new Map<number, number>();
let groupPendingSend: { groupId: number; text: string; mentions: string; clientMsgId: string } | null = null;
const mentionNotices = ref<MentionNotice[]>([]);
const noticeNextBeforeId = ref<number | null>(null);
const noticeUnreadCount = ref(0);
const noticeError = ref("");
let noticeSeq = 0;
const directUnread = ref<Record<number, number>>({});
const lastMarkedRead = new Map<number, number>();
const readInFlight = new Set<number>();
let unreadSeq = 0;
let unreadBusy = false;
const nextBeforeId = ref<number | null>(null);
const chatBusy = ref(false);
const chatError = ref("");
const sending = ref(false);
let chatSeq = 0;
let pendingRealtimeRefresh = false;
let pendingGroupRefresh = false;
let chatSocket: ReturnType<typeof connectChatSocket> | null = null;
let lastHealthyPoll = 0;
let pendingSend: { peerId: number; text: string; clientMsgId: string } | null = null;
const contactRequests = ref<ContactRequest[]>([]);
const contactsBusy = ref(false);
const contactsError = ref("");
let contactsSeq = 0;
const showOlder = ref(false);
const focusId = ref("");
const messageScroll = ref<HTMLElement | null>(null);
const voiceActive = ref(false);
const interim = ref("");
let recognition: any = null;
let toastTimer: ReturnType<typeof setTimeout> | undefined;
const nav: { id: Page; label: string; icon: string }[] = [
  { id: "chat", label: "消息", icon: "message" },
  { id: "contacts", label: "联系人", icon: "users" },
  { id: "requests", label: "好友申请", icon: "userPlus" },
  { id: "notices", label: "通知", icon: "bell" },
  { id: "tasks", label: "我的待办", icon: "checkSquare" },
  { id: "settings", label: "设置", icon: "settings" },
];
watch(
  data,
  (v) => {
    try {
      if (currentAccount.value)
        localStorage.setItem(
          accountStateKey(currentAccount.value.id),
          JSON.stringify(v),
        );
    } catch {}
  },
  { deep: true },
);
watch(knownPeers, (peers) => {
  if (!currentAccount.value) return;
  try { localStorage.setItem(peerCacheKey(currentAccount.value.id), JSON.stringify(peers)); } catch {}
}, { deep: true });
watch(activeId, () => {
  mentionPickerOpen.value = false;
  mentionSelections.value = {};
  showSummary.value = false;
  showOlder.value = false;
  stopVoice();
  directMessages.value = [];
  groupMessages.value = [];
  directSyncCursor.value = null;
  groupSyncCursor.value = null;
  nextBeforeId.value = null;
  groupNextBeforeId.value = null;
  chatError.value = "";
  chatSeq++;
  chatBusy.value = false;
  if (directPeer(activeId.value)) void refreshDirectChat();
  if (serverGroupId(activeId.value)) void refreshGroupChat();
  nextTick(scrollBottom);
});
watch(page, () => {
  stopVoice();
  infoOpen.value = false;
  if (page.value !== "chat") mobileChatOpen.value = false;
  if (page.value === "contacts" || page.value === "requests")
    void refreshContacts();
  if (page.value === "notices") void refreshNotices();
  if (page.value === "chat" && directPeer(activeId.value)) void refreshDirectChat();
  if (page.value === "chat" && serverGroupId(activeId.value)) void refreshGroupChat();
});
function directPeer(id: string): number | null {
  if (!/^server-user-[1-9]\d*$/.test(id)) return null;
  const peerId = Number(id.slice(12));
  return Number.isSafeInteger(peerId) ? peerId : null;
}
function serverGroupId(id: string): number | null {
  if (!/^server-group-[1-9]\d*$/.test(id)) return null;
  const groupId = Number(id.slice(13));
  return Number.isSafeInteger(groupId) ? groupId : null;
}
const realGroups = computed<Conversation[]>(() =>
  serverGroups.value.map((g) => ({
    id: `server-group-${g.id}`, name: g.name, kind: "group",
    memberIds: g.memberIds.map((id) => id === currentAccount.value?.id ? selfId : `user-${id}`),
    ownerId: g.ownerId === currentAccount.value?.id ? selfId : `user-${g.ownerId}`,
    unread: g.unreadCount, color: "teal", initials: g.name[0] || "群",
  })),
);
const realConversations = computed<Conversation[]>(() =>
  knownPeers.value.map((p) => ({
    id: `server-user-${p.id}`,
    name: p.nickname,
    kind: "direct",
    memberIds: [selfId, `user-${p.id}`],
    unread: directUnread.value[p.id] ?? 0,
    color: "violet",
    initials: p.nickname[0] || "友",
  })),
);
const allConversations = computed(() => [...realGroups.value, ...realConversations.value]);
watch(allConversations, (items) => {
  if (currentAccount.value && (!activeId.value || !items.some((c) => c.id === activeId.value)))
    activeId.value = items[0]?.id || "";
});
const active = computed(() => allConversations.value.find((c) => c.id === activeId.value));
const messages = computed<Message[]>(() =>
  directPeer(activeId.value)
    ? directMessages.value.map((m) => ({
        id: `server-message-${m.id}`,
        conversationId: activeId.value,
        senderId: m.senderId === currentAccount.value?.id ? selfId : `user-${m.senderId}`,
        text: m.body,
        time: new Date(m.createdAt).toLocaleString("zh-CN", { hour: "2-digit", minute: "2-digit" }),
        kind: "text" as const,
      }))
    : serverGroupId(activeId.value)
      ? groupMessages.value.map((m) => ({
          id: `group-message-${m.id}`, conversationId: activeId.value,
          senderId: m.senderId === currentAccount.value?.id ? selfId : `user-${m.senderId}`,
          text: m.body,
          time: new Date(m.createdAt).toLocaleString("zh-CN", { hour: "2-digit", minute: "2-digit" }),
          kind: "text" as const,
        }))
      : [],
);
const visibleMessages = computed(() => messages.value);
const conversations = computed(() =>
  allConversations.value.filter(
    (c) =>
      c.name.toLowerCase().includes(search.value.toLowerCase()) ||
      (c.id === activeId.value ? messages.value : []).some(
        (m) =>
          m.conversationId === c.id &&
          m.text.toLowerCase().includes(search.value.toLowerCase()),
      ),
  ),
);
const friends = computed<Person[]>(() =>
  contactProfiles.value.map((p) => ({
    id: `user-${p.id}`,
    name: p.nickname,
    handle: p.handle,
    initials: p.nickname[0] || "友",
    color: "violet",
    status: "offline",
    note: "已建立好友关系",
  })),
);
const received = computed(() =>
  contactRequests.value.filter((r) => r.toId === currentAccount.value?.id),
);
const sent = computed(() =>
  contactRequests.value.filter((r) => r.fromId === currentAccount.value?.id),
);
const unread = computed(() => noticeUnreadCount.value);
const openTasks = computed(
  () => data.value.todos.filter((t) => !t.done).length,
);
const canWrite = computed(
  () =>
    !!active.value &&
    (directPeer(activeId.value)
      ? contactProfiles.value.some((p) => p.id === directPeer(activeId.value))
      : active.value.kind === "group"
      ? active.value.memberIds.includes(selfId)
      : active.value.memberIds.every(
          (id) => id === selfId || data.value.friendIds.includes(id),
        )),
);
const candidates = computed(() =>
  friends.value.filter((p) => !active.value?.memberIds.includes(p.id)),
);
const focusCount = computed(
  () =>
    messages.value.filter(
      (m) =>
        m.senderId !== selfId &&
        data.value.preferences.focusWords
          .split(/[,，]/)
          .some((w) => w.trim() && m.text.includes(w.trim())),
    ).length,
);
const summarySources = computed(() =>
  (range.value === "recent" ? messages.value.slice(-5) : messages.value)
    .filter((m) => m.kind !== "system" && m.senderId !== selfId)
    .slice(-3),
);
function person(id: string): Person {
  return (
    friends.value.find((p) => p.id === id) ||
    data.value.people.find((p) => p.id === id) ||
    (id === selfId ? data.value.people[0] : undefined) ||
    { id, name: `用户 ${id.replace(/^user-/, "")}`, handle: "",
      initials: "友", color: "violet", status: "offline", note: "" }
  );
}
function last(c: Conversation) {
  if (directPeer(c.id) || serverGroupId(c.id)) {
    const lastMessage = c.id === activeId.value ? messages.value.at(-1) : undefined;
    return lastMessage;
  }
  return undefined;
}
function notify(s: string) {
  toast.value = s;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (toast.value = ""), 3500);
}
function scrollBottom() {
  if (messageScroll.value)
    messageScroll.value.scrollTop = messageScroll.value.scrollHeight;
}
function openChat(id: string, source?: string) {
  const wasActive = activeId.value === id;
  const previous = allConversations.value.find((x) => x.id === id);
  const unreadMessages = id === activeId.value ? messages.value : [];
  unreadMarker.value = previous?.unread
    ? unreadMessages.at(-previous.unread)?.id || ""
    : "";
  mobileChatOpen.value = true;
  activeId.value = id;
  page.value = "chat";
  assistantOpen.value = false;
  if (source) {
    showOlder.value = true;
    focusId.value = source;
    nextTick(() =>
      document
        .getElementById(`msg-${source}`)
        ?.scrollIntoView({ behavior: "smooth", block: "center" }),
    );
    setTimeout(() => (focusId.value = ""), 3000);
  } else nextTick(scrollBottom);
  if (directPeer(id) && wasActive && !chatBusy.value) void refreshDirectChat();
  if (serverGroupId(id) && wasActive && !chatBusy.value) void refreshGroupChat();
}
function mergeDirect(incoming: DirectMessage[]) {
  const byId = new Map(directMessages.value.map((m) => [m.id, m]));
  for (const m of incoming) byId.set(m.id, m);
  directMessages.value = [...byId.values()].sort((a, b) => a.id - b.id);
}
async function refreshUnread() {
  const accountId = currentAccount.value?.id;
  if (!accountId || unreadBusy) return;
  unreadBusy = true;
  const sequence = ++unreadSeq;
  try {
    const counts = await listUnreadDirect();
    if (sequence !== unreadSeq || currentAccount.value?.id !== accountId) return;
    directUnread.value = Object.fromEntries(counts.map(({ peerId, unreadCount }) => [peerId, unreadCount]));
    for (const { peerId, unreadCount } of counts) {
      if (unreadCount > 0 && !contactProfiles.value.some((p) => p.id === peerId)
          && !loadingPeers.has(peerId)) void loadUnreadPeer(peerId, accountId);
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
    const profile = await friendProfile(peerId);
    if (currentAccount.value?.id !== accountId) return;
    if (!contactProfiles.value.some((p) => p.id === peerId))
      contactProfiles.value = [...contactProfiles.value, profile];
    if (!knownPeers.value.some((p) => p.id === peerId))
      knownPeers.value = [...knownPeers.value, profile];
  } catch {
    // An old sender may no longer be a friend. Do not reveal the profile.
  } finally {
    loadingPeers.delete(peerId);
  }
}
async function markVisibleRead(peerId: number) {
  const accountId = currentAccount.value?.id;
  if (!accountId || page.value !== "chat" || directPeer(activeId.value) !== peerId
      || document.hidden || readInFlight.has(peerId)) return;
  const lastReceivedId = directMessages.value
    .filter((m) => m.senderId === peerId && m.recipientId === accountId).at(-1)?.id;
  if (!lastReceivedId || (lastMarkedRead.get(peerId) ?? 0) >= lastReceivedId) return;
  readInFlight.add(peerId);
  try {
    await markDirectRead(peerId, lastReceivedId);
    if (currentAccount.value?.id !== accountId) return;
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
async function refreshDirectChat() {
  const peerId = directPeer(activeId.value);
  const accountId = currentAccount.value?.id;
  if (!peerId || !accountId || chatBusy.value) return;
  const sequence = ++chatSeq;
  chatBusy.value = true;
  try {
    if (directSyncCursor.value === null) {
      const result = await loadDirectMessages(peerId);
      if (sequence !== chatSeq || currentAccount.value?.id !== accountId || directPeer(activeId.value) !== peerId) return;
      nextBeforeId.value = result.nextBeforeId;
      mergeDirect(result.messages);
      const newest = result.messages.at(-1)?.id ?? 0;
      directSyncCursor.value = resumeCursor(savedCursor(accountId, "direct", peerId), newest);
      saveCursor(accountId, "direct", peerId, directSyncCursor.value);
    }
    const resumed = await syncForward(directSyncCursor.value,
      (cursor) => syncDirectMessages(peerId, cursor), (messages, cursor) => {
        if (sequence !== chatSeq || currentAccount.value?.id !== accountId || directPeer(activeId.value) !== peerId) return false;
        mergeDirect(messages);
        directSyncCursor.value = cursor;
        saveCursor(accountId, "direct", peerId, cursor);
        return true;
      });
    if (resumed === null) return;
    chatError.value = "";
    await nextTick();
    scrollBottom();
    void markVisibleRead(peerId);
  } catch (error) {
    if (sequence === chatSeq) chatError.value = error instanceof Error ? error.message : "消息加载失败";
  } finally {
    if (sequence === chatSeq) chatBusy.value = false;
    if (pendingRealtimeRefresh && sequence === chatSeq) {
      pendingRealtimeRefresh = false;
      void refreshDirectChat();
    }
  }
}
async function loadOlderDirect() {
  const peerId = directPeer(activeId.value);
  const beforeId = nextBeforeId.value;
  const accountId = currentAccount.value?.id;
  if (!peerId || !beforeId || !accountId || chatBusy.value) return;
  const sequence = ++chatSeq;
  chatBusy.value = true;
  try {
    const result = await loadDirectMessages(peerId, beforeId);
    if (sequence !== chatSeq || currentAccount.value?.id !== accountId || directPeer(activeId.value) !== peerId) return;
    mergeDirect(result.messages);
    nextBeforeId.value = result.nextBeforeId;
    chatError.value = "";
  } catch (error) {
    if (sequence === chatSeq) chatError.value = error instanceof Error ? error.message : "加载历史失败";
  } finally {
    if (sequence === chatSeq) chatBusy.value = false;
    if (pendingRealtimeRefresh && sequence === chatSeq) {
      pendingRealtimeRefresh = false;
      void refreshDirectChat();
    }
  }
}
async function refreshGroups() {
  const accountId = currentAccount.value?.id;
  if (!accountId) return;
  const sequence = ++groupsSeq;
  try {
    const result = await listGroups();
    if (sequence !== groupsSeq || currentAccount.value?.id !== accountId) return;
    const byId = new Map(serverGroups.value.map((g) => [g.id, g]));
    for (const group of result.items) byId.set(group.id, group);
    serverGroups.value = [...byId.values()].sort((a, b) => b.id - a.id);
    groupsNextCursor.value = result.nextBeforeId;
    if (!activeId.value || !allConversations.value.some((c) => c.id === activeId.value))
      activeId.value = allConversations.value[0]?.id || "";
  } catch (error) {
    if (sequence === groupsSeq && !serverGroups.value.length)
      chatError.value = error instanceof Error ? error.message : "讨论室加载失败";
  }
}
async function loadMoreGroups() {
  const cursor = groupsNextCursor.value;
  const accountId = currentAccount.value?.id;
  if (!cursor || !accountId) return;
  const sequence = ++groupsSeq;
  try {
    const result = await listGroups(cursor);
    if (sequence !== groupsSeq || currentAccount.value?.id !== accountId) return;
    const byId = new Map(serverGroups.value.map((g) => [g.id, g]));
    for (const group of result.items) byId.set(group.id, group);
    serverGroups.value = [...byId.values()];
    groupsNextCursor.value = result.nextBeforeId;
  } catch (error) {
    notify(error instanceof Error ? error.message : "讨论室加载失败");
  }
}
function mergeGroup(incoming: GroupMessage[]) {
  const byId = new Map(groupMessages.value.map((m) => [m.id, m]));
  for (const message of incoming) byId.set(message.id, message);
  groupMessages.value = [...byId.values()].sort((a, b) => a.id - b.id);
}
async function markVisibleGroupRead(groupId: number) {
  const accountId = currentAccount.value?.id;
  if (!accountId || document.hidden || page.value !== "chat" || serverGroupId(activeId.value) !== groupId
      || groupReadInFlight.has(groupId)) return;
  const last = groupMessages.value.at(-1)?.id;
  if (!last || (groupLastRead.get(groupId) ?? 0) >= last) return;
  groupReadInFlight.add(groupId);
  try {
    await markGroupRead(groupId, last);
    if (currentAccount.value?.id !== accountId) return;
    groupLastRead.set(groupId, last);
    const group = serverGroups.value.find((g) => g.id === groupId);
    if (group) group.unreadCount = 0;
    void refreshGroups();
  } catch {
    // A later sync retries without clearing the count.
  } finally {
    groupReadInFlight.delete(groupId);
  }
}
async function refreshGroupChat() {
  const groupId = serverGroupId(activeId.value);
  const accountId = currentAccount.value?.id;
  if (!groupId || !accountId) return;
  if (chatBusy.value) { pendingGroupRefresh = true; return; }
  const sequence = ++chatSeq;
  chatBusy.value = true;
  try {
    if (groupSyncCursor.value === null) {
      const result = await loadGroupMessages(groupId);
      if (sequence !== chatSeq || currentAccount.value?.id !== accountId || serverGroupId(activeId.value) !== groupId) return;
      groupNextBeforeId.value = result.nextBeforeId;
      mergeGroup(result.messages);
      const newest = result.messages.at(-1)?.id ?? 0;
      groupSyncCursor.value = resumeCursor(savedCursor(accountId, "group", groupId), newest);
      saveCursor(accountId, "group", groupId, groupSyncCursor.value);
    }
    const resumed = await syncForward(groupSyncCursor.value,
      (cursor) => syncGroupMessages(groupId, cursor), (messages, cursor) => {
        if (sequence !== chatSeq || currentAccount.value?.id !== accountId || serverGroupId(activeId.value) !== groupId) return false;
        mergeGroup(messages);
        groupSyncCursor.value = cursor;
        saveCursor(accountId, "group", groupId, cursor);
        return true;
      });
    if (resumed === null) return;
    chatError.value = "";
    await nextTick();
    if (focusId.value)
      document.getElementById(`msg-${focusId.value}`)?.scrollIntoView({ behavior: "smooth", block: "center" });
    else scrollBottom();
    void markVisibleGroupRead(groupId);
  } catch (error) {
    if (sequence === chatSeq) chatError.value = error instanceof Error ? error.message : "群消息加载失败";
  } finally {
    if (sequence === chatSeq) chatBusy.value = false;
    if (pendingGroupRefresh && sequence === chatSeq) {
      pendingGroupRefresh = false;
      void refreshGroupChat();
    }
  }
}
async function loadOlderGroup() {
  const groupId = serverGroupId(activeId.value);
  const cursor = groupNextBeforeId.value;
  const accountId = currentAccount.value?.id;
  if (!groupId || !cursor || !accountId || chatBusy.value) return;
  const sequence = ++chatSeq;
  chatBusy.value = true;
  try {
    const result = await loadGroupMessages(groupId, cursor);
    if (sequence !== chatSeq || currentAccount.value?.id !== accountId || serverGroupId(activeId.value) !== groupId) return;
    mergeGroup(result.messages);
    groupNextBeforeId.value = result.nextBeforeId;
    chatError.value = "";
  } catch (error) {
    if (sequence === chatSeq) chatError.value = error instanceof Error ? error.message : "加载历史失败";
  } finally {
    if (sequence === chatSeq) chatBusy.value = false;
    if (pendingGroupRefresh && sequence === chatSeq) {
      pendingGroupRefresh = false;
      void refreshGroupChat();
    }
  }
}
async function refreshNotices() {
  const accountId = currentAccount.value?.id;
  if (!accountId) return;
  const sequence = ++noticeSeq;
  try {
    const result = await listMentionNotices();
    if (sequence !== noticeSeq || currentAccount.value?.id !== accountId) return;
    const byId = new Map(mentionNotices.value.map((n) => [n.id, n]));
    for (const item of result.items) byId.set(item.id, item);
    mentionNotices.value = [...byId.values()].sort((a, b) => b.id - a.id);
    noticeNextBeforeId.value = result.nextBeforeId;
    noticeUnreadCount.value = result.unreadCount;
    noticeError.value = "";
  } catch (error) {
    if (sequence === noticeSeq) noticeError.value = error instanceof Error ? error.message : "通知加载失败";
  }
}
async function loadOlderNotices() {
  const cursor = noticeNextBeforeId.value;
  const accountId = currentAccount.value?.id;
  if (!cursor || !accountId) return;
  const sequence = ++noticeSeq;
  try {
    const result = await listMentionNotices(cursor);
    if (sequence !== noticeSeq || currentAccount.value?.id !== accountId) return;
    const byId = new Map(mentionNotices.value.map((n) => [n.id, n]));
    for (const item of result.items) byId.set(item.id, item);
    mentionNotices.value = [...byId.values()].sort((a, b) => b.id - a.id);
    noticeNextBeforeId.value = result.nextBeforeId;
    noticeUnreadCount.value = result.unreadCount;
    noticeError.value = "";
  } catch (error) {
    noticeError.value = error instanceof Error ? error.message : "加载历史通知失败";
  }
}
function mentionMember(id: string) {
  const memberId = Number(id.slice(5));
  const token = `@${person(id).name}#${memberId}`;
  mentionSelections.value[memberId] = token;
  composer.value += `${composer.value && !/\s$/.test(composer.value) ? " " : ""}${token} `;
  mentionPickerOpen.value = false;
}
function onChatEvent(kind?: "direct" | "group" | "notice") {
  if (!currentAccount.value) return;
  if (!kind || kind === "direct") void refreshUnread();
  if (!kind || kind === "group") void refreshGroups();
  if (!kind || kind === "notice") void refreshNotices();
  if ((!kind || kind === "direct") && page.value === "chat" && directPeer(activeId.value)) {
    if (chatBusy.value) pendingRealtimeRefresh = true;
    else void refreshDirectChat();
  }
  if ((!kind || kind === "group") && page.value === "chat" && serverGroupId(activeId.value))
    void refreshGroupChat();
}
const chatPoll = setInterval(() => {
  if (!currentAccount.value || document.hidden) return;
  // Keep periodic recovery even when the socket is healthy (Pub/Sub is not durable).
  if (chatSocket?.connected() && Date.now() - lastHealthyPoll < 30_000) return;
  lastHealthyPoll = Date.now();
  onChatEvent();
}, 5000);
function switchAuthMode(next: "login" | "register" | "reset") {
  authMode.value = next;
  registerStep.value = "details";
  pendingRegistration.value = null;
  authError.value = "";
  authSuccess.value = "";
  authPassword.value = "";
  authConfirm.value = "";
  authPhoneCode.value = "";
  authEmailCode.value = "";
  authLoginCode.value = "";
  codeHints.value = { phone: "", email: "", login: "", bind: "" };
}
function editRegistration() {
  registerStep.value = "details";
  pendingRegistration.value = null;
  authPhoneCode.value = "";
  authEmailCode.value = "";
  codeHints.value.phone = "";
  codeHints.value.email = "";
  authError.value = "";
}
function waitSeconds(
  purpose: CodePurpose,
  type: ContactType,
  raw: string,
): number {
  void codeTick.value;
  const key = `${purpose}:${type}:${normalizeContact(type, raw)}`;
  return Math.max(
    0,
    Math.ceil(((resendAt.value[key] || 0) - Date.now()) / 1000),
  );
}
async function requestCode(
  purpose: CodePurpose,
  type: ContactType,
  raw: string,
  slot: CodeSlot,
) {
  if (codeBusy.value[slot]) return;
  authError.value = "";
  bindError.value = "";
  codeBusy.value[slot] = true;
  try {
    if (!validContact(type, raw))
      throw new Error(
        type === "phone"
          ? "请先输入正确的中国大陆手机号"
          : "请先输入正确的邮箱地址",
      );
    const contact = normalizeContact(type, raw);
    const response = await sendCode(purpose, type, contact);
    resendAt.value[`${purpose}:${type}:${contact}`] =
      Date.now() + response.retryAfterSeconds * 1000;
    codeHints.value[slot] = response.devCode
      ? `本地开发验证码：${response.devCode}（${Math.ceil(response.expiresInSeconds / 60)} 分钟有效；不会真实发送）`
      : "验证码已发送";
    codeTick.value = Date.now();
  } catch (error) {
    if (slot === "bind")
      bindError.value =
        error instanceof Error ? error.message : "无法获取验证码";
    else
      authError.value =
        error instanceof Error ? error.message : "无法获取验证码";
  } finally {
    codeBusy.value[slot] = false;
  }
}
function applyAccount(account: Account) {
  chatSocket?.close();
  pendingRealtimeRefresh = false;
  pendingGroupRefresh = false;
  chatSeq++;
  unreadSeq++;
  unreadBusy = false;
  directUnread.value = {};
  lastMarkedRead.clear();
  readInFlight.clear();
  knownPeers.value = loadKnownPeers(account);
  directMessages.value = [];
  groupMessages.value = [];
  directSyncCursor.value = null;
  groupSyncCursor.value = null;
  serverGroups.value = [];
  groupsNextCursor.value = null;
  groupLastRead.clear();
  groupReadInFlight.clear();
  groupPendingSend = null;
  mentionNotices.value = [];
  noticeNextBeforeId.value = null;
  noticeUnreadCount.value = 0;
  noticeError.value = "";
  noticeSeq++;
  mentionSelections.value = {};
  groupsSeq++;
  activeId.value = "";
  data.value = load(account);
  data.value.displayName = account.name;
  data.value.handle = account.handle;
  data.value.people[0]!.name = account.name;
  data.value.people[0]!.handle = account.handle;
  data.value.people[0]!.initials = account.name[0]!;
  currentAccount.value = account;
  page.value = "chat";
  void refreshContacts();
  void refreshUnread();
  void refreshGroups();
  void refreshNotices();
  chatSocket = connectChatSocket(onChatEvent);
  nextTick(scrollBottom);
}
async function refreshContacts() {
  const accountId = currentAccount.value?.id;
  if (!accountId) return;
  const sequence = ++contactsSeq;
  contactsBusy.value = true;
  contactsError.value = "";
  try {
    const [profiles, requests] = await Promise.all([
      listFriends(),
      listRequests(),
    ]);
    if (sequence !== contactsSeq || currentAccount.value?.id !== accountId)
      return;
    contactProfiles.value = profiles.items;
    friendsNextCursor.value = profiles.nextCursor;
    requestsNextCursor.value = requests.nextCursor;
    const merged = new Map(knownPeers.value.map((p) => [p.id, p]));
    for (const profile of profiles.items) merged.set(profile.id, profile);
    knownPeers.value = [...merged.values()];
    contactRequests.value = requests.items;
  } catch (error) {
    if (sequence !== contactsSeq || currentAccount.value?.id !== accountId)
      return;
    if (error instanceof ApiError && error.status === 401) {
      clearSession();
      currentAccount.value = null;
      authError.value = "登录已过期，请重新登录";
    } else {
      contactsError.value =
        error instanceof Error ? error.message : "无法加载好友";
    }
  } finally {
    if (sequence === contactsSeq) contactsBusy.value = false;
  }
}
async function loadMoreFriends() {
  const cursor = friendsNextCursor.value;
  const accountId = currentAccount.value?.id;
  if (!cursor || !accountId || contactsBusy.value) return;
  const sequence = contactsSeq;
  contactsBusy.value = true;
  try {
    const page = await listFriends(cursor);
    if (sequence !== contactsSeq || currentAccount.value?.id !== accountId) return;
    const allProfiles = new Map(contactProfiles.value.map((p) => [p.id, p]));
    for (const profile of page.items) allProfiles.set(profile.id, profile);
    contactProfiles.value = [...allProfiles.values()];
    friendsNextCursor.value = page.nextCursor;
    const merged = new Map(knownPeers.value.map((p) => [p.id, p]));
    for (const profile of page.items) merged.set(profile.id, profile);
    knownPeers.value = [...merged.values()];
  } catch (error) {
    contactsError.value = error instanceof Error ? error.message : "加载好友失败";
  } finally {
    if (sequence === contactsSeq) contactsBusy.value = false;
  }
}
async function loadMoreRequests() {
  const cursor = requestsNextCursor.value;
  const accountId = currentAccount.value?.id;
  if (!cursor || !accountId || contactsBusy.value) return;
  const sequence = contactsSeq;
  contactsBusy.value = true;
  try {
    const page = await listRequests(cursor);
    if (sequence !== contactsSeq || currentAccount.value?.id !== accountId) return;
    contactRequests.value = [...contactRequests.value, ...page.items];
    requestsNextCursor.value = page.nextCursor;
  } catch (error) {
    contactsError.value = error instanceof Error ? error.message : "加载申请失败";
  } finally {
    if (sequence === contactsSeq) contactsBusy.value = false;
  }
}
onMounted(async () => {
  if (!sessionToken()) return;
  try {
    applyAccount(await currentUser());
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) clearSession();
    authError.value =
      error instanceof Error ? error.message : "无法恢复登录状态";
  } finally {
    authRestoring.value = false;
  }
});
async function submitAuth() {
  if (authBusy.value) return;
  authError.value = "";
  authSuccess.value = "";
  authBusy.value = true;
  try {
    if (authMode.value === "register") {
      if (registerStep.value === "details") {
        if (!validPassword(authPassword.value))
          throw new Error("密码需为 8–72 位，并包含字母和数字");
        if (authPassword.value !== authConfirm.value)
          throw new Error("两次输入的密码不一致");
        const input = {
          name: authName.value,
          handle: authHandle.value,
          phone: authPhone.value,
          email: authEmail.value,
          password: authPassword.value,
        };
        const { name, handle, phone, email } = validateRegistration(input);
        await checkRegistration({
          name,
          handle,
          phone,
          email,
          password: input.password,
        });
        pendingRegistration.value = {
          name,
          handle,
          phone,
          email,
          password: input.password,
        };
        authPhoneCode.value = "";
        authEmailCode.value = "";
        registerStep.value = "verify";
        return;
      }
      if (!pendingRegistration.value) throw new Error("请先填写注册资料");
      const account = await register({
        ...pendingRegistration.value,
        phoneCode: authPhoneCode.value,
        emailCode: authEmailCode.value,
      });
      contactType.value = account.phone ? "phone" : "email";
      authIdentifier.value = account.phone || account.email || "";
      switchAuthMode("login");
      authSuccess.value = "注册成功！请用已验证的手机号或邮箱登录。";
      return;
    }
    if (!validContact(contactType.value, authIdentifier.value))
      throw new Error("请输入有效的手机号或邮箱");
    if (authMode.value === "reset") {
      if (!validPassword(authPassword.value))
        throw new Error("密码需为 8–72 位，并包含字母和数字");
      if (authPassword.value !== authConfirm.value)
        throw new Error("两次输入的密码不一致");
      if (!/^[0-9]{6}$/.test(authLoginCode.value))
        throw new Error("请输入 6 位验证码");
      await resetPassword(
        contactType.value,
        normalizeContact(contactType.value, authIdentifier.value),
        authLoginCode.value,
        authPassword.value,
      );
      switchAuthMode("login");
      authSuccess.value = "密码已重设，请用新密码登录。其他设备需要重新登录。";
      return;
    }
    const account =
      loginMethod.value === "password"
        ? await loginWithPassword(
            contactType.value,
            normalizeContact(contactType.value, authIdentifier.value),
            authPassword.value,
          )
        : await loginWithCode(
            contactType.value,
            normalizeContact(contactType.value, authIdentifier.value),
            authLoginCode.value,
          );
    applyAccount(account);
    authPassword.value = "";
  } catch (error) {
    authError.value =
      error instanceof Error ? error.message : "操作失败，请稍后再试";
  } finally {
    authBusy.value = false;
  }
}
async function signOut() {
  try {
    await logout();
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) clearSession();
    else {
      notify(error instanceof Error ? error.message : "退出失败，请稍后重试");
      return;
    }
  }
  stopVoice();
  chatSocket?.close();
  chatSocket = null;
  pendingRealtimeRefresh = false;
  pendingGroupRefresh = false;
  contactsSeq++;
  chatSeq++;
  unreadSeq++;
  unreadBusy = false;
  directUnread.value = {};
  lastMarkedRead.clear();
  readInFlight.clear();
  contactProfiles.value = [];
  friendsNextCursor.value = null;
  requestsNextCursor.value = null;
  knownPeers.value = [];
  directMessages.value = [];
  groupMessages.value = [];
  directSyncCursor.value = null;
  groupSyncCursor.value = null;
  serverGroups.value = [];
  groupsNextCursor.value = null;
  groupLastRead.clear();
  groupReadInFlight.clear();
  groupPendingSend = null;
  mentionNotices.value = [];
  noticeNextBeforeId.value = null;
  noticeUnreadCount.value = 0;
  noticeError.value = "";
  noticeSeq++;
  mentionSelections.value = {};
  groupsSeq++;
  contactRequests.value = [];
  currentAccount.value = null;
  page.value = "chat";
  authPassword.value = "";
  bindPassword.value = "";
  bindCode.value = "";
  bindValue.value = "";
  bindError.value = "";
  bindSuccess.value = "";
  switchAuthMode("login");
}
async function bindContact() {
  if (!currentAccount.value || !missingContact.value || bindBusy.value) return;
  bindError.value = "";
  bindSuccess.value = "";
  bindBusy.value = true;
  try {
    const type = missingContact.value;
    const updated = await bindAccountContact(
      type,
      normalizeContact(type, bindValue.value),
      bindPassword.value,
      bindCode.value,
    );
    currentAccount.value = updated;
    bindValue.value = "";
    bindPassword.value = "";
    bindCode.value = "";
    bindSuccess.value =
      type === "phone" ? "手机号已绑定，可用于登录" : "邮箱已绑定，可用于登录";
  } catch (error) {
    bindError.value = error instanceof Error ? error.message : "绑定失败";
  } finally {
    bindBusy.value = false;
  }
}
async function send() {
  const text = composer.value.trim();
  if (!text || !active.value || !canWrite.value || sending.value) return;
  const peerId = directPeer(activeId.value);
  if (peerId) {
    const accountId = currentAccount.value?.id;
    if (!accountId) return;
    const clientMsgId = pendingSend?.peerId === peerId && pendingSend.text === text
      ? pendingSend.clientMsgId : crypto.randomUUID();
    pendingSend = { peerId, text, clientMsgId };
    sending.value = true;
    try {
      const saved = await sendDirectMessage(peerId, clientMsgId, text);
      if (currentAccount.value?.id !== accountId || directPeer(activeId.value) !== peerId) return;
      mergeDirect([saved]);
      composer.value = "";
      pendingSend = null;
      chatError.value = "";
      nextTick(scrollBottom);
    } catch (error) {
      chatError.value = error instanceof Error ? error.message : "发送失败，请重试";
    } finally {
      sending.value = false;
    }
    return;
  }
  const groupId = serverGroupId(activeId.value);
  if (!groupId) return;
  const accountId = currentAccount.value?.id;
  if (!accountId) return;
  const mentionedUserIds = Object.entries(mentionSelections.value)
    .filter(([, token]) => text.includes(token))
    .map(([id]) => Number(id)).sort((a, b) => a - b);
  const mentions = mentionedUserIds.join(",");
  const clientMsgId = groupPendingSend?.groupId === groupId && groupPendingSend.text === text
    && groupPendingSend.mentions === mentions
    ? groupPendingSend.clientMsgId : crypto.randomUUID();
  groupPendingSend = { groupId, text, mentions, clientMsgId };
  sending.value = true;
  try {
    const saved = await sendGroupMessage(groupId, clientMsgId, text, mentionedUserIds);
    if (currentAccount.value?.id !== accountId || serverGroupId(activeId.value) !== groupId) return;
    mergeGroup([saved]);
    composer.value = "";
    groupPendingSend = null;
    mentionSelections.value = {};
    chatError.value = "";
    nextTick(scrollBottom);
  } catch (error) {
    chatError.value = error instanceof Error ? error.message : "发送失败，请重试";
  } finally {
    sending.value = false;
  }
}
async function requestFriend() {
  if (friendBusy.value) return;
  const type = friendSearchType.value;
  const query = type === "phone"
    ? normalizeContact("phone", friendQuery.value)
    : friendQuery.value.trim().replace(/^@/, "").toLowerCase();
  if (type === "phone" ? !validContact("phone", query) : !/^[a-z0-9_]{3,20}$/.test(query)) {
    friendError.value = type === "phone" ? "请输入正确的中国大陆手机号" : "请输入 3–20 位字母、数字或下划线账号";
    return;
  }
  friendBusy.value = true;
  friendError.value = "";
  try {
    const identifier = type === "phone" ? { phone: query } : { handle: query };
    await inviteFriend(identifier, friendNote.value.trim());
    modal.value = null;
    page.value = "requests";
    await refreshContacts();
    notify("如果账号存在且可接收申请，对方会收到邀请");
  } catch (error) {
    friendError.value = error instanceof Error ? error.message : "发送失败";
  } finally {
    friendBusy.value = false;
  }
}
async function resolveRequest(
  id: number,
  action: "accept" | "reject" | "withdraw",
) {
  try {
    await resolveFriendRequest(id, action);
    await refreshContacts();
    notify("申请状态已更新");
  } catch (error) {
    notify(error instanceof Error ? error.message : "处理失败");
    await refreshContacts();
  }
}
async function removeFriend(p: Person) {
  if (!confirm(`移除 ${p.name}？过往私聊仍可查看。`)) return;
  try {
    await deleteFriend(Number(p.id.slice(5)));
    await refreshContacts();
    notify("已移除好友");
  } catch (error) {
    notify(error instanceof Error ? error.message : "移除失败");
  }
}
function openFriendChat(p: Person) {
  openChat(`server-${p.id}`);
}
function openModal(m: Modal) {
  modal.value = m;
  inviteIds.value = [];
  friendSearchType.value = "phone";
  friendQuery.value = "";
  friendNote.value = "";
  friendError.value = "";
  groupName.value = "";
}
function toggleInvite(id: string) {
  inviteIds.value = inviteIds.value.includes(id)
    ? inviteIds.value.filter((x) => x !== id)
    : [...inviteIds.value, id];
}
async function createGroup() {
  const name = groupName.value.trim();
  if (!name || inviteIds.value.length > 20 || groupBusy.value) return;
  groupBusy.value = true;
  try {
    const created = await createGroupChat(name, inviteIds.value.map((id) => Number(id.slice(5))));
    serverGroups.value = [created, ...serverGroups.value];
    modal.value = null;
    openChat(`server-group-${created.id}`);
  } catch (error) {
    friendError.value = error instanceof Error ? error.message : "建群失败";
  } finally {
    groupBusy.value = false;
  }
}
async function addMembers() {
  const groupId = serverGroupId(activeId.value);
  if (
    !active.value ||
    !groupId ||
    active.value.ownerId !== selfId ||
    !inviteIds.value.length ||
    inviteIds.value.length > 20 ||
    active.value.memberIds.length + inviteIds.value.length > 50
  ) {
    notify("最多 50 人，每次邀请不超过 20 人");
    return;
  }
  if (groupBusy.value) return;
  groupBusy.value = true;
  try {
    const updated = await inviteGroupMembers(groupId, inviteIds.value.map((id) => Number(id.slice(5))));
    serverGroups.value = serverGroups.value.map((g) => g.id === groupId ? updated : g);
    modal.value = null;
    notify("邀请已发送，新成员可以查看加入后的消息");
  } catch (error) {
    friendError.value = error instanceof Error ? error.message : "邀请失败";
  } finally {
    groupBusy.value = false;
  }
}
async function openNotice(n: MentionNotice) {
  try {
    const id = `server-group-${n.groupId}`;
    if (!serverGroups.value.some((g) => g.id === n.groupId)) await refreshGroups();
    if (!serverGroups.value.some((g) => g.id === n.groupId)) throw new Error("无法找到此讨论室");
    const target = await getGroupMessage(n.groupId, n.messageId);
    if (!n.readAt) {
      await markMentionRead(n.id);
      n.readAt = new Date().toISOString();
      noticeUnreadCount.value = Math.max(0, noticeUnreadCount.value - 1);
    }
    openChat(id);
    if (activeId.value !== id) return;
    await nextTick();
    mergeGroup([target]);
    focusId.value = `group-message-${target.id}`;
    await nextTick();
    document.getElementById(`msg-${focusId.value}`)?.scrollIntoView({ behavior: "smooth", block: "center" });
    setTimeout(() => (focusId.value = ""), 3000);
  } catch (error) {
    noticeError.value = error instanceof Error ? error.message : "打开通知失败";
    notify(noticeError.value);
  }
}
async function readAllNotices() {
  try {
    await markAllMentionsRead();
    mentionNotices.value = mentionNotices.value.map((n) => ({
      ...n, readAt: n.readAt || new Date().toISOString(),
    }));
    noticeUnreadCount.value = 0;
    void refreshNotices();
  } catch (error) {
    noticeError.value = error instanceof Error ? error.message : "标记失败";
  }
}
function addTask() {
  const title = taskDraft.value.trim();
  if (!title) return;
  data.value.todos.unshift({
    id: uid("t"),
    title,
    due: "待安排",
    done: false,
    sourceConversationId: page.value === "chat" ? activeId.value : undefined,
    sourceId: page.value === "chat" ? messages.value.at(-1)?.id : undefined,
  });
  taskDraft.value = "";
  page.value = "tasks";
  notify("待办已创建");
}
function reset() {
  if (!confirm("重置本地待办、通知和偏好演示数据？")) return;
  data.value = initialState();
  activeId.value = allConversations.value[0]?.id || "";
  notify("本地演示数据已重置，服务端聊天记录不受影响");
}
function stopVoice() {
  if (recognition) {
    recognition.onend = null;
    recognition.stop();
    recognition = null;
  }
  voiceActive.value = false;
  interim.value = "";
}
function toggleVoice() {
  if (voiceActive.value) {
    stopVoice();
    return;
  }
  const Ctor =
    (window as any).SpeechRecognition ||
    (window as any).webkitSpeechRecognition;
  if (!Ctor) {
    notify("此浏览器不支持语音转写");
    return;
  }
  const r = new Ctor();
  r.lang = "zh-CN";
  r.continuous = true;
  r.interimResults = true;
  r.onresult = (event: any) => {
    let partial = "";
    for (let i = event.resultIndex; i < event.results.length; i++) {
      const text = event.results[i][0].transcript;
      if (event.results[i].isFinal)
        composer.value += `${composer.value ? " " : ""}${text}`;
      else partial += text;
    }
    interim.value = partial;
  };
  r.onerror = (event: any) => {
    notify(
      event.error === "not-allowed" ? "麦克风权限未开启" : "语音转写已停止",
    );
    stopVoice();
  };
  r.onend = () => {
    recognition = null;
    voiceActive.value = false;
    interim.value = "";
  };
  try {
    r.start();
    recognition = r;
    voiceActive.value = true;
  } catch {
    notify("无法启动语音转写");
  }
}
function visibility() {
  if (document.hidden) stopVoice();
  else if (currentAccount.value) {
    void refreshUnread();
    void refreshGroups();
    void refreshNotices();
    if (page.value === "chat" && directPeer(activeId.value)) void refreshDirectChat();
    if (page.value === "chat" && serverGroupId(activeId.value)) void refreshGroupChat();
  }
}
document.addEventListener("visibilitychange", visibility);
onBeforeUnmount(() => {
  stopVoice();
  chatSocket?.close();
  clearInterval(chatPoll);
  clearTimeout(toastTimer);
  clearInterval(codeInterval);
  document.removeEventListener("visibilitychange", visibility);
});
</script>

<template>
  <div v-if="authRestoring" class="auth-loading" role="status">
    正在恢复登录状态…
  </div>
  <div v-else-if="!loggedIn" class="auth-shell">
    <div class="auth-visual">
      <div class="brand light">
        <span class="brand-mark"><AppIcon name="sparkles" /></span> IM 助手
      </div>
      <div class="auth-art">
        <div class="art-card">
          <span class="avatar peach tiny">夏</span>我们周五一起确认方案吧。
        </div>
        <div class="art-card second">
          <span class="avatar blue tiny">诺</span>好，我把下一步记下来。
        </div>
        <div class="art-glow">✦</div>
      </div>
      <div class="auth-copy">
        <p class="overline">CONNECT · COLLABORATE · CREATE</p>
        <h1>聊得清楚，<br />事情也更有着落。</h1>
        <p>把重要对话、好友协作与下一步计划，放在同一个舒适的空间。</p>
      </div>
      <small>IM ASSISTANT · FRONTEND PREVIEW</small>
    </div>
    <div class="auth-form-wrap">
      <form class="auth-card" @submit.prevent="submitAuth">
        <span class="eyebrow-chip">账号已接入用户服务</span>
        <h2>
          {{
            authMode === "login"
              ? "欢迎回来"
              : authMode === "reset"
                ? "重设密码"
                : registerStep === "verify"
                  ? "验证联系方式"
                  : "创建你的账号"
          }}
        </h2>
        <p>
          {{
            authMode === "login"
              ? "用已绑定的手机号或邮箱登录。"
              : authMode === "reset"
                ? "验证已绑定的手机号或邮箱，然后设置新密码。"
                : registerStep === "verify"
                  ? "确认验证码后，才会创建你的账号。"
                  : "先填写注册资料，下一步验证联系方式。"
          }}
        </p>
        <div
          v-if="authMode === 'login' || authMode === 'reset'"
          class="auth-tabs"
          role="group"
          aria-label="联系方式"
        >
          <button
            type="button"
            :class="{ active: contactType === 'phone' }"
            :aria-pressed="contactType === 'phone'"
            @click="
              contactType = 'phone';
              authIdentifier = '';
              authError = '';
            "
          >
            手机号
          </button>
          <button
            type="button"
            :class="{ active: contactType === 'email' }"
            :aria-pressed="contactType === 'email'"
            @click="
              contactType = 'email';
              authIdentifier = '';
              authError = '';
            "
          >
            邮箱
          </button>
        </div>
        <template v-if="authMode === 'register' && registerStep === 'details'">
          <label for="auth-name">昵称</label>
          <input
            id="auth-name"
            v-model="authName"
            autocomplete="nickname"
            maxlength="24"
            placeholder="例如：岳蕾"
            required
          />
          <label for="auth-handle">唯一账号（用于添加好友）</label>
          <div class="input-prefix">
            <span>@</span>
            <input
              id="auth-handle"
              v-model="authHandle"
              autocomplete="username"
              maxlength="20"
              placeholder="your_handle"
              required
            />
          </div>
          <p class="auth-contact-hint">
            手机号和邮箱至少填一项；都填将关联同一个账号。
          </p>
          <label for="auth-phone">手机号 <span>可选</span></label>
          <input
            id="auth-phone"
            v-model="authPhone"
            type="tel"
            inputmode="tel"
            autocomplete="tel"
            maxlength="20"
            placeholder="请输入 11 位手机号"
          />
          <label for="auth-email">邮箱地址 <span>可选</span></label>
          <input
            id="auth-email"
            v-model="authEmail"
            type="email"
            inputmode="email"
            autocomplete="email"
            maxlength="254"
            placeholder="name@example.com"
          />
        </template>
        <template
          v-if="
            authMode === 'register' &&
            registerStep === 'verify' &&
            pendingRegistration
          "
        >
          <p class="auth-contact-hint">
            资料已通过服务端检查。请验证以下每一项联系方式；如果修改联系方式，请重新获取验证码。
          </p>
          <template v-if="pendingRegistration.phone">
            <label for="auth-phone-code"
              >手机号 · {{ pendingRegistration.phone }}</label
            >
            <div class="verification-field">
              <input
                id="auth-phone-code"
                v-model="authPhoneCode"
                inputmode="numeric"
                autocomplete="one-time-code"
                maxlength="6"
                placeholder="输入手机验证码"
                required
              />
              <button
                type="button"
                :disabled="
                  codeBusy.phone ||
                  waitSeconds('register', 'phone', pendingRegistration.phone) >
                    0
                "
                @click="
                  requestCode(
                    'register',
                    'phone',
                    pendingRegistration.phone!,
                    'phone',
                  )
                "
              >
                {{
                  waitSeconds("register", "phone", pendingRegistration.phone)
                    ? `${waitSeconds("register", "phone", pendingRegistration.phone)} 秒`
                    : "获取验证码"
                }}
              </button>
            </div>
            <p v-if="codeHints.phone" class="demo-code" role="status">
              {{ codeHints.phone }}
            </p>
          </template>
          <template v-if="pendingRegistration.email">
            <label for="auth-email-code"
              >邮箱 · {{ pendingRegistration.email }}</label
            >
            <div class="verification-field">
              <input
                id="auth-email-code"
                v-model="authEmailCode"
                inputmode="numeric"
                autocomplete="one-time-code"
                maxlength="6"
                placeholder="输入邮箱验证码"
                required
              />
              <button
                type="button"
                :disabled="
                  codeBusy.email ||
                  waitSeconds('register', 'email', pendingRegistration.email) >
                    0
                "
                @click="
                  requestCode(
                    'register',
                    'email',
                    pendingRegistration.email!,
                    'email',
                  )
                "
              >
                {{
                  waitSeconds("register", "email", pendingRegistration.email)
                    ? `${waitSeconds("register", "email", pendingRegistration.email)} 秒`
                    : "获取验证码"
                }}
              </button>
            </div>
            <p v-if="codeHints.email" class="demo-code" role="status">
              {{ codeHints.email }}
            </p>
          </template>
        </template>
        <label v-if="authMode !== 'register'" for="auth-identifier">{{
          contactType === "phone" ? "手机号" : "邮箱地址"
        }}</label>
        <input
          v-if="authMode !== 'register'"
          id="auth-identifier"
          v-model="authIdentifier"
          :type="contactType === 'phone' ? 'tel' : 'email'"
          :inputmode="contactType === 'phone' ? 'tel' : 'email'"
          :autocomplete="contactType === 'phone' ? 'tel' : 'email'"
          :placeholder="
            contactType === 'phone' ? '请输入 11 位手机号' : 'name@example.com'
          "
          :maxlength="contactType === 'phone' ? 20 : 254"
          required
        />
        <div v-if="authMode === 'login'" class="login-method">
          <button
            type="button"
            :class="{ active: loginMethod === 'password' }"
            @click="
              loginMethod = 'password';
              authError = '';
            "
          >
            密码登录
          </button>
          <button
            type="button"
            :class="{ active: loginMethod === 'code' }"
            @click="
              loginMethod = 'code';
              authError = '';
            "
          >
            验证码登录
          </button>
        </div>
        <template
          v-if="
            authMode === 'reset' ||
            (authMode === 'login' && loginMethod === 'code')
          "
        >
          <label for="auth-login-code">{{
            contactType === "phone" ? "手机验证码" : "邮箱验证码"
          }}</label>
          <div class="verification-field">
            <input
              id="auth-login-code"
              v-model="authLoginCode"
              inputmode="numeric"
              autocomplete="one-time-code"
              maxlength="6"
              placeholder="输入 6 位验证码"
              required
            />
            <button
              type="button"
              :disabled="
                codeBusy.login ||
                waitSeconds(
                  authMode === 'reset' ? 'reset' : 'login',
                  contactType,
                  authIdentifier,
                ) > 0
              "
              @click="
                requestCode(
                  authMode === 'reset' ? 'reset' : 'login',
                  contactType,
                  authIdentifier,
                  'login',
                )
              "
            >
              {{
                waitSeconds(
                  authMode === "reset" ? "reset" : "login",
                  contactType,
                  authIdentifier,
                )
                  ? `${waitSeconds(authMode === "reset" ? "reset" : "login", contactType, authIdentifier)} 秒`
                  : "获取验证码"
              }}
            </button>
          </div>
          <p v-if="codeHints.login" class="demo-code" role="status">
            {{ codeHints.login }}
          </p>
        </template>
        <label
          v-if="
            authMode === 'reset' ||
            (authMode === 'register' && registerStep === 'details') ||
            (authMode === 'login' && loginMethod === 'password')
          "
          for="auth-password"
          >{{ authMode === "reset" ? "新密码" : "密码" }}</label
        >
        <div
          v-if="
            authMode === 'reset' ||
            (authMode === 'register' && registerStep === 'details') ||
            (authMode === 'login' && loginMethod === 'password')
          "
          class="password-field"
        >
          <input
            id="auth-password"
            v-model="authPassword"
            :type="showPassword ? 'text' : 'password'"
            :autocomplete="
              authMode !== 'login' ? 'new-password' : 'current-password'
            "
            :placeholder="
              authMode !== 'login' ? '至少 8 位，包含字母和数字' : '请输入密码'
            "
            :minlength="authMode !== 'login' ? 8 : undefined"
            required
          />
          <button
            type="button"
            :aria-label="showPassword ? '隐藏密码' : '显示密码'"
            @click="showPassword = !showPassword"
          >
            <AppIcon :name="showPassword ? 'eyeoff' : 'eye'" :size="17" />
          </button>
        </div>
        <template
          v-if="
            authMode === 'reset' ||
            (authMode === 'register' && registerStep === 'details')
          "
        >
          <label for="auth-confirm">确认密码</label>
          <input
            id="auth-confirm"
            v-model="authConfirm"
            :type="showPassword ? 'text' : 'password'"
            autocomplete="new-password"
            placeholder="再次输入密码"
            required
          />
        </template>
        <p v-if="authSuccess" class="success-text" role="status">
          {{ authSuccess }}
        </p>
        <p v-if="authError" class="error-text" role="alert">{{ authError }}</p>
        <button class="primary-button full" type="submit" :disabled="authBusy">
          {{
            authBusy
              ? "处理中…"
              : authMode === "register"
                ? registerStep === "details"
                  ? "下一步 · 验证联系方式"
                  : "验证并创建账号"
                : authMode === "reset"
                  ? "验证并重设密码"
                  : loginMethod === "code"
                    ? "验证码登录"
                    : "密码登录"
          }}
          <AppIcon name="arrowRight" :size="17" />
        </button>
        <button
          v-if="authMode === 'login' && loginMethod === 'password'"
          class="auth-back"
          type="button"
          @click="switchAuthMode('reset')"
        >
          忘记密码？
        </button>
        <button
          v-if="authMode === 'reset'"
          class="auth-back"
          type="button"
          @click="switchAuthMode('login')"
        >
          返回登录
        </button>
        <button
          v-if="authMode === 'register' && registerStep === 'verify'"
          class="auth-back"
          type="button"
          @click="editRegistration"
        >
          返回修改资料
        </button>
        <button
          v-if="authMode !== 'reset'"
          class="auth-switch"
          type="button"
          @click="switchAuthMode(authMode === 'login' ? 'register' : 'login')"
        >
          {{
            authMode === "login" ? "还没有账号？先注册" : "已经有账号？去登录"
          }}
        </button>
        <p class="auth-disclaimer">
          本地开发模式会显示后端生成的验证码，不发送短信或邮件。请使用测试联系方式与密码。
        </p>
      </form>
    </div>
  </div>
  <div
    v-else
    class="app-shell"
    :style="{ '--message-size': `${data.preferences.fontSize}px` }"
  >
    <nav class="rail" aria-label="主导航">
      <div class="rail-logo" title="IM 助手">
        <AppIcon name="sparkles" :size="22" />
      </div>
      <div class="rail-links">
        <button
          v-for="n in nav"
          :key="n.id"
          :class="['rail-link', { active: page === n.id }]"
          :aria-label="n.label"
          :aria-current="page === n.id ? 'page' : undefined"
          :title="n.label"
          @click="page = n.id"
        >
          <AppIcon :name="n.icon" :size="21" /><span
            v-if="
              (n.id === 'requests' &&
                received.some((r) => r.state === 'PENDING')) ||
              (n.id === 'chat' && Object.values(directUnread).some((count) => count > 0)) ||
              (n.id === 'notices' && unread)
            "
            class="rail-dot"
          />
        </button>
      </div>
      <button
        class="rail-profile"
        aria-label="个人设置"
        @click="page = 'settings'"
      >
        <span class="avatar violet tiny">{{ data.displayName[0] }}</span>
      </button>
    </nav>
    <div v-if="page === 'chat'" class="chat-layout">
      <aside class="conversation-panel">
        <div class="panel-top">
          <div>
            <p class="overline">WORKSPACE</p>
            <h1>
              消息
              <span class="count-pill">{{ allConversations.length }}</span>
            </h1>
          </div>
          <button
            class="icon-button outlined"
            title="创建讨论室"
            aria-label="创建讨论室"
            @click="openModal('group')"
          >
            <AppIcon name="plus" />
          </button>
        </div>
        <div class="search-box">
          <AppIcon name="search" :size="17" /><input
            v-model="search"
            aria-label="搜索会话或消息"
            placeholder="搜索会话或消息"
          /><button
            v-if="search"
            class="clear-button"
            aria-label="清除搜索"
            @click="search = ''"
          >
            ×
          </button>
        </div>
        <div class="section-label">
          <span>最近会话</span><span>{{ conversations.length }}</span>
        </div>
        <div class="conversation-scroll">
          <button
            v-for="c in conversations"
            :key="c.id"
            :class="['conversation-item', { selected: activeId === c.id }]"
            :aria-label="c.unread ? `${c.name}，${c.unread} 条未读消息` : c.name"
            @click="openChat(c.id)"
          >
            <span :class="['avatar', c.color]">{{ c.initials }}</span
            ><span class="conversation-content"
              ><span class="conversation-line"
                ><strong>{{ c.name }}</strong
                ><small>{{ last(c)?.time || "刚刚" }}</small></span
              ><span class="conversation-line preview"
                ><span
                  >{{
                    last(c)?.senderId === "me" && last(c)?.kind !== "system"
                      ? "我："
                      : ""
                  }}{{ last(c)?.text || "开始一段对话" }}</span
                ><span v-if="c.unread" class="unread-badge">{{
                  c.unread > 99 ? "99+" : c.unread
                }}</span></span
              ></span
            >
          </button>
          <button v-if="groupsNextCursor" class="load-more" @click="loadMoreGroups">加载更多讨论室</button>
          <div v-if="!conversations.length" class="empty-list">
            没有找到相关会话
          </div>
        </div>
        <div class="sidebar-bottom">
          <div class="sidebar-help">
            <span class="help-icon"
              ><AppIcon name="sparkles" :size="17"
            /></span>
            <div>
              <strong>好好聊，轻松做</strong>
              <p>从对话中发现下一步</p>
            </div>
          </div>
          <span class="demo-chip"
            ><span class="status-dot" />好友私聊与讨论室已接入</span
          >
        </div>
      </aside>
      <main
        v-if="active"
        :class="['chat-main', { 'mobile-open': mobileChatOpen }]"
      >
        <header class="chat-header">
          <div class="chat-heading">
            <button
              class="icon-button mobile-back"
              aria-label="返回会话列表"
              @click="mobileChatOpen = false"
            >
              <AppIcon name="left" /></button
            ><span :class="['avatar', active.color, 'header-avatar']">{{
              active.initials
            }}</span>
            <div>
              <div class="title-row">
                <h2>{{ active.name }}</h2>
                <span v-if="active.kind === 'group'" class="tag">讨论室</span>
              </div>
              <p>
                {{
                  active.kind === "group"
                    ? `${active.memberIds.length} 位成员 · ${active.description || "一起协作"}`
                    : canWrite
                      ? `@${person(active.memberIds.find((id) => id !== "me") || "me").handle} · 好友`
                      : "已解除好友关系 · 历史消息只读"
                }}
              </p>
            </div>
          </div>
          <div class="header-actions">
            <button
              class="icon-button"
              title="接上讨论"
              aria-label="打开讨论助手"
              @click="assistantOpen = !assistantOpen"
            >
              <AppIcon name="sparkles" /></button
            ><button
              class="icon-button"
              title="会话信息"
              aria-label="打开会话信息"
              @click="infoOpen = true"
            >
              <AppIcon name="info" />
            </button>
          </div>
        </header>
        <div class="chat-notice">
          <AppIcon name="shield" :size="15" />
          消息已保存；在线时实时推送，断线后自动补拉。翻译与 AI 总结仍在开发中。
        </div>
        <div ref="messageScroll" class="message-scroll">
          <div class="messages-inner">
            <div v-if="chatError" class="error-text" role="alert">
              {{ chatError }}
              <button class="text-button" type="button" :disabled="chatBusy" @click="directPeer(activeId) ? refreshDirectChat() : refreshGroupChat()">重试</button>
            </div>
            <button v-if="directPeer(activeId) && nextBeforeId" class="load-more" :disabled="chatBusy" @click="loadOlderDirect">
              {{ chatBusy ? "加载中…" : "查看更早消息" }}
            </button>
            <button v-if="serverGroupId(activeId) && groupNextBeforeId" class="load-more" :disabled="chatBusy" @click="loadOlderGroup">
              {{ chatBusy ? "加载中…" : "查看更早消息" }}
            </button>
            <p v-if="chatBusy && !messages.length" role="status">正在加载消息…</p>
            <p v-if="!chatBusy && !chatError && !messages.length" class="empty-list">还没有消息，发一条开始聊天吧</p>
            <div class="day-divider"><span>今天 · 讨论继续</span></div>
            <template v-for="m in visibleMessages" :key="m.id"
              ><div v-if="unreadMarker === m.id" class="unread-divider">
                以下是未读消息
              </div>
              <div
                v-if="m.kind === 'system'"
                :id="`msg-${m.id}`"
                class="system-message"
              >
                {{ m.text }}
              </div>
              <div
                v-else
                :id="`msg-${m.id}`"
                :class="[
                  'message-row',
                  { mine: m.senderId === 'me', focused: focusId === m.id },
                ]"
              >
                <span
                  v-if="m.senderId !== 'me'"
                  :class="[
                    'avatar',
                    person(m.senderId).color,
                    'message-avatar',
                  ]"
                  >{{ person(m.senderId).initials }}</span
                >
                <div class="message-stack">
                  <div class="message-meta">
                    <span>{{
                      m.senderId === "me" ? "我" : person(m.senderId).name
                    }}</span
                    ><time>{{ m.time }}</time>
                  </div>
                  <div class="bubble">
                    <span v-if="m.kind === 'voice'" class="voice-label"
                      ><AppIcon name="mic" :size="14" /> 语音转写</span
                    >{{ m.text }}
                  </div>
                  <div
                    v-if="m.translation && data.preferences.showTranslations"
                    class="translation"
                  >
                    <AppIcon name="globe" :size="13" /> 译文 ·
                    {{ m.translation }}
                  </div>
                  <div v-if="m.status" class="delivery">仅本地 · 未同步</div>
                </div>
              </div></template
            >
          </div>
        </div>
        <div class="composer-zone">
          <div v-if="voiceActive" class="voice-interim" role="status">
            <span class="recording-dot" /> 正在转写，仅你可见
            {{ interim ? `· ${interim}` : "· 请开始说话" }}
            <button @click="stopVoice">停止</button>
          </div>
          <div v-if="!canWrite" class="read-only">
            <AppIcon name="lock" :size="16" /> 已解除好友关系，此对话为只读
          </div>
          <div v-else class="composer">
            <textarea
              v-model="composer"
              rows="2"
              aria-label="输入消息"
              placeholder="写点什么，继续这段讨论…"
              @keydown.enter.exact.prevent="send"
            />
            <div v-if="mentionPickerOpen && serverGroupId(activeId)" class="mention-picker" role="group" aria-label="选择要提醒的群成员">
              <button v-for="id in active.memberIds.filter((member) => member !== 'me')"
                :key="id" type="button" class="text-button" @click="mentionMember(id)">
                @{{ person(id).name }} · {{ id.replace('user-', '') }}
              </button>
              <span v-if="active.memberIds.length <= 1">暂无其他成员</span>
            </div>
            <div class="composer-tools">
              <div class="tool-left">
                <button
                  class="tool-button"
                  :class="{ recording: voiceActive }"
                  :title="voiceActive ? '停止语音转写' : '语音转写（手动发送）'"
                  :aria-label="voiceActive ? '停止语音转写' : '开始语音转写'"
                  @click="toggleVoice"
                >
                  <AppIcon name="mic" :size="18" /></button
                ><button
                  v-if="serverGroupId(activeId)"
                  class="tool-button"
                  title="选择要提醒的成员"
                  aria-label="提及成员"
                  @click="mentionPickerOpen = !mentionPickerOpen"
                >
                  <AppIcon name="at" :size="18" /></button
                ><span class="composer-tip"
                  >Enter 发送 · Shift + Enter 换行</span
                >
              </div>
              <button
                class="send-button"
                :disabled="!composer.trim() || sending"
                @click="send"
              >
                发送 <AppIcon name="arrowUp" :size="16" />
              </button>
            </div>
          </div>
        </div>
      </main>
      <aside :class="['assistant-panel', { open: assistantOpen }]">
        <div class="assistant-header">
          <div>
            <div class="assistant-brand">
              <span class="assistant-mark"
                ><AppIcon name="sparkles" :size="17" /></span
              ><strong>接上讨论</strong><span class="beta">演示</span>
            </div>
            <p>从消息里理清思路，接住下一步。</p>
          </div>
          <button
            class="icon-button close-assistant"
            aria-label="关闭助手"
            @click="assistantOpen = false"
          >
            <AppIcon name="x" />
          </button>
        </div>
        <div class="assistant-scroll">
          <div class="assist-hero">
            <span class="assist-hero-icon"
              ><AppIcon name="sparkles" :size="23"
            /></span>
            <p>让重要的事<br /><strong>自然浮现。</strong></p>
            <small>选择会话，整理讨论中的线索。</small>
          </div>
          <div class="assist-section">
            <div class="assist-label">
              <AppIcon name="alignLeft" :size="17" /><strong>对话摘要</strong>
            </div>
            <p class="assist-description">
              基于演示消息展示来源；真实 AI 分析待接入。
            </p>
            <div class="segmented">
              <button
                :class="{ active: range === 'recent' }"
                @click="
                  range = 'recent';
                  showSummary = false;
                "
              >
                最近 5 条</button
              ><button
                :class="{ active: range === 'all' }"
                @click="
                  range = 'all';
                  showSummary = false;
                "
              >
                全部消息
              </button>
            </div>
            <button class="soft-button" @click="showSummary = true">
              <AppIcon name="sparkles" :size="15" /> 查看讨论线索
              <AppIcon name="arrowRight" :size="15" />
            </button>
            <div v-if="showSummary" class="summary-card">
              <small
                >消息线索 ·
                {{ range === "recent" ? "最近 5 条" : "全部消息" }}</small
              >
              <p v-if="!summarySources.length">当前范围暂无可整理的消息。</p>
              <template v-else
                ><p>以下是值得回看的发言，点击定位到原消息：</p>
                <button
                  v-for="m in summarySources"
                  :key="m.id"
                  class="source-link"
                  @click="openChat(m.conversationId, m.id)"
                >
                  {{ person(m.senderId).name }}：{{ m.text.slice(0, 38)
                  }}{{ m.text.length > 38 ? "…" : "" }}
                  <AppIcon name="arrowUpRight" :size="14" /></button
              ></template>
            </div>
          </div>
          <div class="assist-section">
            <div class="assist-label">
              <AppIcon name="checkSquare" :size="17" /><strong
                >记下下一步</strong
              >
            </div>
            <p class="assist-description">新建待办需要你亲自确认。</p>
            <input
              v-model="taskDraft"
              class="task-draft"
              aria-label="待办标题"
              placeholder="例如：确认本周接口时间"
              maxlength="100"
              @keydown.enter="addTask"
            /><button
              class="soft-button"
              :disabled="!taskDraft.trim()"
              @click="addTask"
            >
              <AppIcon name="plus" :size="15" /> 确认创建待办
            </button>
          </div>
          <div class="assist-section highlight-section">
            <div class="assist-label">
              <AppIcon name="target" :size="17" /><strong>关注词</strong>
            </div>
            <p>
              当前会话有 <strong>{{ focusCount }}</strong> 条消息提到关注词。
            </p>
            <button
              @click="
                page = 'settings';
                assistantOpen = false;
              "
            >
              管理关注词 <AppIcon name="arrowRight" :size="14" />
            </button>
          </div>
        </div>
        <div class="assistant-footer">
          <span class="status-dot" /> 摘要、翻译、同步待接入接口
        </div>
      </aside>
    </div>
    <main v-else class="page-shell">
      <header class="page-header">
        <div>
          <p class="overline">
            IM ASSISTANT / {{ nav.find((n) => n.id === page)?.label }}
          </p>
          <h1>{{ nav.find((n) => n.id === page)?.label }}</h1>
          <p class="page-subtitle">
            {{
              {
                contacts: "熟悉的人，让协作更顺畅。",
                requests: "从一次打招呼，开始一段好对话。",
                notices: "重要动态，随时掌握。",
                tasks: "把讨论中的下一步妥帖地记下来。",
                settings: "把工作空间调整到喜欢的样子。",
              }[page]
            }}
          </p>
        </div>
        <button
          v-if="page === 'contacts' || page === 'requests'"
          class="primary-button"
          @click="openModal('friend')"
        >
          <AppIcon name="userPlus" :size="17" /> 添加好友
        </button>
      </header>
      <div v-if="page === 'contacts'" class="page-content">
        <p v-if="contactsError" class="error-text" role="alert">
          {{ contactsError }}
        </p>
        <p v-if="contactsBusy" role="status">正在加载好友…</p>
        <div class="stat-row">
          <div class="stat-card">
            <span>我的好友</span
            ><strong>{{ friends.length.toString().padStart(2, "0") }}</strong
            ><small>好友关系已同步</small>
          </div>
          <div class="stat-card">
            <span>共同讨论室</span
            ><strong>{{
              realGroups.length.toString()
                .padStart(2, "0")
            }}</strong
            ><small>和伙伴一起推进想法</small>
          </div>
        </div>
        <div class="content-card">
          <div class="card-heading">
            <h2>好友列表</h2>
            <span>{{ friends.length }} 位好友</span>
            <button
              class="text-button"
              type="button"
              :disabled="contactsBusy"
              @click="refreshContacts"
            >
              刷新
            </button>
          </div>
          <div v-for="p in friends" :key="p.id" class="person-row">
            <span :class="['avatar', p.color]">{{ p.initials }}</span>
            <div class="person-detail">
              <strong>{{ p.name }}</strong
              ><span>@{{ p.handle }} · {{ p.note }}</span>
            </div>
            <button class="outline-button" @click="openFriendChat(p)">
              <AppIcon name="message" :size="15" /> 发消息</button
            ><button
              class="icon-button subdued"
              :aria-label="`移除好友 ${p.name}`"
              @click="removeFriend(p)"
            >
              <AppIcon name="userMinus" :size="17" />
            </button>
          </div>
          <div v-if="!friends.length && !contactsBusy" class="empty-state">
            还没有好友，试试通过唯一账号添加。
          </div>
          <button v-if="friendsNextCursor" class="text-button" :disabled="contactsBusy" @click="loadMoreFriends">加载更多好友</button>
        </div>
      </div>
      <div v-if="page === 'requests'" class="page-content">
        <p v-if="contactsError" class="error-text" role="alert">
          {{ contactsError }}
        </p>
        <p v-if="contactsBusy" role="status">正在加载申请…</p>
        <div class="content-card">
          <div class="card-heading">
            <h2>收到的申请</h2>
            <span
              >{{
                received.filter((r) => r.state === "PENDING").length
              }}
              条待处理</span
            >
            <button
              class="text-button"
              type="button"
              :disabled="contactsBusy"
              @click="refreshContacts"
            >
              刷新
            </button>
          </div>
          <div v-if="!received.length && !contactsBusy" class="empty-state">
            <AppIcon name="inbox" :size="26" /><strong>暂时没有新申请</strong>
            <p>新的好友邀请会出现在这里。</p>
          </div>
          <div v-for="r in received" :key="r.id" class="request-row">
            <span class="avatar violet">{{ r.peerNickname[0] }}</span>
            <div class="request-body">
              <strong
                >{{ r.peerNickname }} <span>@{{ r.peerHandle }}</span></strong
              >
              <p>{{ r.note }}</p>
              <small
                >{{ new Date(r.createdAt).toLocaleString("zh-CN") }} ·
                {{
                  r.state === "PENDING"
                    ? "等待你处理"
                    : r.state === "ACCEPTED"
                      ? "已同意"
                      : r.state === "REJECTED"
                        ? "已拒绝"
                        : "已撤回"
                }}</small
              >
            </div>
            <div v-if="r.state === 'PENDING'" class="row-actions">
              <button
                class="outline-button"
                @click="resolveRequest(r.id, 'reject')"
              >
                拒绝</button
              ><button
                class="primary-button compact"
                @click="resolveRequest(r.id, 'accept')"
              >
                同意
              </button>
            </div>
          </div>
        </div>
        <div class="content-card">
          <div class="card-heading">
            <h2>我发出的申请</h2>
            <span
              >{{
                sent.filter((r) => r.state === "PENDING").length
              }}
              条待回应</span
            >
          </div>
          <div v-if="!sent.length && !contactsBusy" class="empty-state mini">
            还没有发出的申请。通过唯一账号搜索新朋友吧。
          </div>
          <div v-for="r in sent" :key="r.id" class="request-row">
            <span class="avatar violet">{{ r.peerNickname[0] }}</span>
            <div class="request-body">
              <strong
                >{{ r.peerNickname }} <span>@{{ r.peerHandle }}</span></strong
              >
              <p>{{ r.note }}</p>
              <small
                >{{ new Date(r.createdAt).toLocaleString("zh-CN") }} ·
                {{
                  r.state === "PENDING"
                    ? "等待回应"
                    : r.state === "ACCEPTED"
                      ? "已同意"
                      : r.state === "REJECTED"
                        ? "已拒绝"
                        : "已撤回"
                }}</small
              >
            </div>
            <button
              v-if="r.state === 'PENDING'"
              class="text-button"
              @click="resolveRequest(r.id, 'withdraw')"
            >
              撤回
            </button>
          </div>
        </div>
        <button v-if="requestsNextCursor" class="text-button" :disabled="contactsBusy" @click="loadMoreRequests">加载更早申请</button>
      </div>
      <div v-if="page === 'notices'" class="page-content">
        <div class="content-card">
          <div class="card-heading">
            <h2>点名提醒</h2>
            <button
              class="text-button"
              :disabled="!unread"
              @click="readAllNotices"
            >
              全部标为已读
            </button>
          </div>
          <p v-if="noticeError" class="error-text" role="alert">{{ noticeError }}
            <button class="text-button" @click="refreshNotices">重试</button>
          </p>
          <button
            v-for="n in mentionNotices"
            :key="n.id"
            :class="['notice-row', { unread: !n.readAt }]"
            @click="openNotice(n)"
          >
            <span class="notice-icon"
              ><AppIcon name="at" :size="19" /></span
            ><span class="notice-body"
              ><strong>{{ person(`user-${n.senderId}`).name }} 在「{{ n.groupName }}」中提到了你</strong
              ><span>{{ n.body.slice(0, 100) }}</span></span
            ><small>{{ new Date(n.createdAt).toLocaleString("zh-CN") }}</small
            ><span v-if="!n.readAt" class="notice-dot" />
          </button>
          <p v-if="!mentionNotices.length && !noticeError" class="empty-list">还没有点名提醒</p>
          <button v-if="noticeNextBeforeId" class="text-button" @click="loadOlderNotices">加载更早提醒</button>
        </div>
      </div>
      <div v-if="page === 'tasks'" class="page-content">
        <div class="stat-row">
          <div class="stat-card">
            <span>进行中</span
            ><strong>{{ openTasks.toString().padStart(2, "0") }}</strong
            ><small>一次做好一件事</small>
          </div>
          <div class="stat-card">
            <span>已完成</span
            ><strong>{{
              data.todos
                .filter((t) => t.done)
                .length.toString()
                .padStart(2, "0")
            }}</strong
            ><small>每一步都值得记录</small>
          </div>
        </div>
        <div class="content-card">
          <div class="card-heading">
            <h2>我的待办</h2>
            <span>共 {{ data.todos.length }} 项</span>
          </div>
          <div class="add-task">
            <AppIcon name="plus" :size="18" /><input
              v-model="taskDraft"
              maxlength="100"
              aria-label="新待办标题"
              placeholder="添加一项待办，按 Enter 确认"
              @keydown.enter="addTask"
            /><button :disabled="!taskDraft.trim()" @click="addTask">
              添加
            </button>
          </div>
          <div
            v-for="t in data.todos"
            :key="t.id"
            :class="['todo-row', { done: t.done }]"
          >
            <label class="todo-label"
              ><input
                v-model="t.done"
                type="checkbox"
                :aria-label="`完成 ${t.title}`"
              /><span class="custom-check"
                ><AppIcon name="check" :size="13" /></span
              ><span>{{ t.title }}</span></label
            ><span class="due">{{ t.due }}</span
            ><button
              v-if="t.sourceConversationId"
              class="source-small"
              @click="openChat(t.sourceConversationId!, t.sourceId)"
            >
              查看来源 <AppIcon name="arrowUpRight" :size="14" /></button
            ><button
              class="icon-button subdued"
              :aria-label="`删除 ${t.title}`"
              @click="data.todos = data.todos.filter((x) => x.id !== t.id)"
            >
              <AppIcon name="trash" :size="16" />
            </button>
          </div>
          <div v-if="!data.todos.length" class="empty-state">
            一切都安排好了
          </div>
        </div>
      </div>
      <div v-if="page === 'settings'" class="page-content settings-content">
        <div class="content-card">
          <div class="card-heading">
            <h2>个人资料</h2>
            <span>服务端账号资料</span>
          </div>
          <div class="profile-setting">
            <span class="avatar violet large">{{ data.displayName[0] }}</span>
            <div>
              <strong>{{ data.displayName }}</strong
              ><span>@{{ data.handle }}</span>
            </div>
            <button class="outline-button" @click="signOut">退出登录</button>
          </div>
          <div class="account-contacts">
            <div>
              <span>手机号</span
              ><strong>{{ currentAccount?.phone || "未绑定" }}</strong>
            </div>
            <div>
              <span>邮箱</span
              ><strong>{{ currentAccount?.email || "未绑定" }}</strong>
            </div>
          </div>
          <form
            v-if="missingContact"
            class="bind-form"
            @submit.prevent="bindContact"
          >
            <strong
              >补绑{{ missingContact === "phone" ? "手机号" : "邮箱" }}</strong
            >
            <p>确认当前密码后，将另一种联系方式绑定到你的账号。</p>
            <label for="bind-value">{{
              missingContact === "phone" ? "手机号" : "邮箱地址"
            }}</label>
            <input
              id="bind-value"
              v-model="bindValue"
              :type="missingContact === 'phone' ? 'tel' : 'email'"
              :autocomplete="missingContact === 'phone' ? 'tel' : 'email'"
              :placeholder="
                missingContact === 'phone'
                  ? '请输入 11 位手机号'
                  : 'name@example.com'
              "
              required
            />
            <label for="bind-code">{{
              missingContact === "phone" ? "手机验证码" : "邮箱验证码"
            }}</label>
            <div class="verification-field">
              <input
                id="bind-code"
                v-model="bindCode"
                inputmode="numeric"
                maxlength="6"
                placeholder="输入 6 位验证码"
                required
              />
              <button
                type="button"
                :disabled="
                  codeBusy.bind ||
                  waitSeconds('bind', missingContact, bindValue) > 0
                "
                @click="requestCode('bind', missingContact, bindValue, 'bind')"
              >
                {{
                  waitSeconds("bind", missingContact, bindValue)
                    ? `${waitSeconds("bind", missingContact, bindValue)} 秒`
                    : "获取验证码"
                }}
              </button>
            </div>
            <p v-if="codeHints.bind" class="demo-code" role="status">
              {{ codeHints.bind }}
            </p>
            <label for="bind-password">当前密码</label>
            <input
              id="bind-password"
              v-model="bindPassword"
              type="password"
              autocomplete="current-password"
              placeholder="输入当前账号密码"
              required
            />
            <p v-if="bindError" class="error-text" role="alert">
              {{ bindError }}
            </p>
            <button class="primary-button" type="submit" :disabled="bindBusy">
              {{ bindBusy ? "处理中…" : "关联到当前账号" }}
            </button>
            <small
              >本地开发模式由后端返回验证码；短信和邮件发送尚未接入。</small
            >
          </form>
          <p v-if="bindSuccess" class="success-text" role="status">
            {{ bindSuccess }}
          </p>
        </div>
        <div class="content-card">
          <div class="card-heading">
            <h2>阅读与提醒</h2>
            <span>按你的节奏来</span>
          </div>
          <div class="setting-row">
            <div>
              <strong>消息字号</strong>
              <p>让聊天文字更容易阅读</p>
            </div>
            <div class="segmented setting-segment">
              <button
                v-for="size in [16, 18, 20] as const"
                :key="size"
                :class="{ active: data.preferences.fontSize === size }"
                @click="data.preferences.fontSize = size"
              >
                {{ size }}px
              </button>
            </div>
          </div>
          <div class="setting-row">
            <div>
              <strong>关注词</strong>
              <p>逗号分隔，助手可提示相关消息</p>
            </div>
            <input
              v-model="data.preferences.focusWords"
              class="setting-input"
              aria-label="关注词"
            />
          </div>
          <div class="setting-row">
            <div>
              <strong>免打扰时段</strong>
              <p>偏好已保存，推送能力待接入</p>
            </div>
            <label class="switch"
              ><input
                v-model="data.preferences.quietHours"
                type="checkbox"
                aria-label="免打扰时段" /><span
            /></label>
          </div>
        </div>
        <div class="content-card">
          <div class="card-heading"><h2>语言与显示</h2></div>
          <div class="setting-row">
            <div>
              <strong>默认翻译目标</strong>
              <p>翻译服务接入后使用</p>
            </div>
            <select
              v-model="data.preferences.translationTarget"
              aria-label="默认翻译目标"
            >
              <option value="zh">简体中文</option>
              <option value="en">English</option>
            </select>
          </div>
          <div class="setting-row">
            <div>
              <strong>显示译文</strong>
              <p>原文始终保留，译文分开展示</p>
            </div>
            <label class="switch"
              ><input
                v-model="data.preferences.showTranslations"
                type="checkbox"
                aria-label="显示译文" /><span
            /></label>
          </div>
          <div class="setting-row">
            <div>
              <strong>时区</strong>
              <p>用于未来的提醒与待办时间</p>
            </div>
            <select v-model="data.preferences.timezone" aria-label="时区">
              <option value="Asia/Shanghai">Asia/Shanghai</option>
              <option value="UTC">UTC</option>
            </select>
          </div>
        </div>
        <div class="content-card danger-card">
          <div>
            <strong>演示数据</strong>
            <p>仅待办、通知和偏好保存在此浏览器本地；聊天记录保存在服务端。</p>
          </div>
          <button class="outline-button" @click="reset">重置演示数据</button>
        </div>
      </div>
    </main>
    <nav v-if="page !== 'chat'" class="mobile-nav" aria-label="移动端导航">
      <button
        v-for="n in nav"
        :key="n.id"
        :class="{ active: page === n.id }"
        @click="page = n.id"
      >
        <AppIcon :name="n.icon" :size="19" /><span>{{ n.label }}</span>
      </button>
    </nav>
    <div
      v-if="infoOpen && active"
      class="drawer-backdrop"
      @click.self="infoOpen = false"
    >
      <aside
        class="info-drawer"
        role="dialog"
        aria-modal="true"
        aria-label="会话信息"
      >
        <header>
          <h2>会话信息</h2>
          <button
            class="icon-button"
            aria-label="关闭"
            @click="infoOpen = false"
          >
            <AppIcon name="x" />
          </button>
        </header>
        <div class="drawer-identity">
          <span :class="['avatar', active.color, 'large']">{{
            active.initials
          }}</span>
          <h3>{{ active.name }}</h3>
          <p>
            {{ active.description || "保持联系，让每一次对话都更有意义。" }}
          </p>
        </div>
        <template v-if="active.kind === 'group'"
          ><div class="drawer-section-heading">
            <strong>成员 · {{ active.memberIds.length }}/50</strong
            ><button
              v-if="active.ownerId === 'me'"
              class="text-button"
              @click="
                infoOpen = false;
                openModal('invite');
              "
            >
              邀请好友
            </button>
          </div>
          <div v-for="id in active.memberIds" :key="id" class="member-row">
            <span :class="['avatar', person(id).color, 'tiny']">{{
              person(id).initials
            }}</span
            ><span>{{ person(id).name }}</span
            ><small v-if="active.ownerId === id">群主</small>
          </div>
          <p v-if="active.ownerId !== 'me'" class="drawer-hint">
            仅群主可邀请自己的好友。
          </p></template
        >
        <div class="drawer-section-heading"><strong>会话偏好</strong></div>
        <label class="drawer-toggle"
          >置顶会话 <input v-model="active.pinned" type="checkbox" /></label
        ><label class="drawer-toggle"
          >消息免打扰 <input v-model="active.muted" type="checkbox"
        /></label>
      </aside>
    </div>
    <div v-if="modal" class="modal-backdrop" @click.self="modal = null">
      <div
        class="modal-card"
        role="dialog"
        aria-modal="true"
        :aria-label="
          modal === 'group'
            ? '创建讨论室'
            : modal === 'invite'
              ? '邀请好友'
              : '添加好友'
        "
      >
        <div class="modal-heading">
          <div>
            <span class="modal-icon"
              ><AppIcon :name="modal === 'friend' ? 'userPlus' : 'users'"
            /></span>
            <h2>
              {{
                modal === "group"
                  ? "创建讨论室"
                  : modal === "invite"
                    ? "邀请好友加入"
                    : "添加好友"
              }}
            </h2>
          </div>
          <button
            class="icon-button"
            aria-label="关闭弹窗"
            @click="modal = null"
          >
            <AppIcon name="x" />
          </button>
        </div>
        <template v-if="modal === 'friend'"
          ><p class="modal-lead">
            输入对方已绑定的手机号或唯一账号，直接发送申请。为保护隐私，不显示查询结果。
          </p>
          <div class="auth-tabs" role="group" aria-label="查找方式">
            <button
              type="button"
              :class="{ active: friendSearchType === 'phone' }"
              :aria-pressed="friendSearchType === 'phone'"
              @click="
                friendSearchType = 'phone';
                friendQuery = '';
                friendError = '';
              "
            >
              手机号
            </button>
            <button
              type="button"
              :class="{ active: friendSearchType === 'handle' }"
              :aria-pressed="friendSearchType === 'handle'"
              @click="
                friendSearchType = 'handle';
                friendQuery = '';
                friendError = '';
              "
            >
              唯一账号
            </button>
          </div>
          <label class="field-label" for="friend-query">{{
            friendSearchType === "phone" ? "对方手机号" : "对方账号"
          }}</label>
          <div v-if="friendSearchType === 'handle'" class="input-prefix">
            <span>@</span
            ><input
              id="friend-query"
              v-model="friendQuery"
              placeholder="输入唯一账号"
              @input="
                friendError = '';
              "
              @keydown.enter.prevent="requestFriend"
            />
          </div>
          <input
            v-else
            id="friend-query"
            v-model="friendQuery"
            type="tel"
            inputmode="tel"
            autocomplete="off"
            placeholder="输入已绑定的手机号"
            @input="
              friendError = '';
            "
            @keydown.enter.prevent="requestFriend"
          />
          <p v-if="friendError" class="error-text" role="alert">
            {{ friendError }}
          </p>
          <label class="field-label" for="friend-note"
            >打个招呼 <span>选填</span></label
          ><textarea
            id="friend-note"
            v-model="friendNote"
            rows="3"
            maxlength="120"
            placeholder="介绍一下自己吧…"
          /><button
            class="primary-button full modal-submit"
            :disabled="!friendQuery.trim() || friendBusy"
            @click="requestFriend"
          >
            发送好友申请
          </button></template
        ><template v-else
          ><p class="modal-lead">
            {{
              modal === "group"
                ? "取一个好名字，再邀请好友加入讨论。"
                : "仅群主可邀请自己的好友，每次最多 20 人。"
            }}
          </p>
          <template v-if="modal === 'group'"
            ><label class="field-label" for="group-name">讨论室名称</label
            ><input
              id="group-name"
              v-model="groupName"
              maxlength="30"
              placeholder="例如：产品共创讨论室"
          /></template>
          <p v-if="friendError" class="error-text" role="alert">{{ friendError }}</p>
          <div class="field-label">
            选择好友 <span>已选 {{ inviteIds.length }} 人</span>
          </div>
          <div class="invite-list">
            <label
              v-for="p in modal === 'invite' ? candidates : friends"
              :key="p.id"
              class="invite-person"
              ><span :class="['avatar', p.color, 'tiny']">{{ p.initials }}</span
              ><span
                >{{ p.name }} <small>@{{ p.handle }}</small></span
              ><input
                type="checkbox"
                :checked="inviteIds.includes(p.id)"
                :disabled="
                  !inviteIds.includes(p.id) &&
                  (inviteIds.length >= 20 ||
                    (modal === 'invite' &&
                      !!active &&
                      active.memberIds.length + inviteIds.length >= 50))
                "
                @change="toggleInvite(p.id)"
            /></label>
            <p
              v-if="modal === 'invite' && !candidates.length"
              class="empty-list"
            >
              好友都已在讨论室中
            </p>
          </div>
          <button v-if="friendsNextCursor" class="text-button" type="button" :disabled="contactsBusy" @click="loadMoreFriends">
            加载更多好友
          </button>
          <button
            class="primary-button full modal-submit"
            :disabled="
              groupBusy || (modal === 'group' ? !groupName.trim() : !inviteIds.length)
            "
            @click="modal === 'group' ? createGroup() : addMembers()"
          >
            {{ modal === "group" ? "创建讨论室" : "确认邀请" }}
          </button></template
        >
      </div>
    </div>
    <div v-if="toast" class="toast" role="status">
      <AppIcon name="info" :size="17" />{{ toast }}
    </div>
  </div>
</template>
