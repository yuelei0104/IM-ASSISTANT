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
import ConversationPanel from "./components/ConversationPanel.vue";
import AssistantPanel from "./components/AssistantPanel.vue";
import AuthShell from "./components/AuthShell.vue";
import MessageList from "./components/MessageList.vue";
import MessageComposer from "./components/MessageComposer.vue";
import ChatHeader from "./components/ChatHeader.vue";
import ContactsPage from "./components/ContactsPage.vue";
import RequestsPage from "./components/RequestsPage.vue";
import NoticesPage from "./components/NoticesPage.vue";
import TasksPage from "./components/TasksPage.vue";
import SettingsPage from "./components/SettingsPage.vue";
import AppModals from "./components/AppModals.vue";
import ChatInfoDrawer from "./components/ChatInfoDrawer.vue";
import {
  accountStateKey,
  type Account,
} from "./authApi";
import type { ContactType } from "./authValidation";
import { friendProfile, type ContactProfile } from "./contactApi";
import type { DirectMessage, MessageRecall } from "./chatApi";
import { getGroup, getGroupMessage, type GroupMessage } from "./groupApi";
import { useGroupAgent } from "./useGroupAgent";
import { useGroupSummaries } from "./useGroupSummaries";
import { useTasks } from "./useTasks";
import { useNotices } from "./useNotices";
import { useContacts } from "./useContacts";
import { useAuthForm } from "./useAuthForm";
import { useHistorySearch } from "./useHistorySearch";
import { useMessageContext } from "./useMessageContext";
import { useChatFeed } from "./useChatFeed";
import { useChatSend } from "./useChatSend";
import { useVoiceInput } from "./useVoiceInput";
import { useGroupTranslation } from "./useGroupTranslation";
import { useRealtime } from "./useRealtime";
import { useAccountSession, peerCacheKey, type ShellPage } from "./useAccountSession";
import { useConversationView } from "./useConversationView";
import { useGroupAdmin } from "./useGroupAdmin";
import { directPeer, serverGroupId } from "./chatIds";
import { onSessionExpired } from "./session";
import type { MentionNotice } from "./noticeApi";
import { initialState, type DemoState, type Message, type Person } from "./demo";

type Page = ShellPage;
type Modal = "group" | "invite" | "friend" | null;

const currentAccount = ref<Account | null>(null);
const data = ref<DemoState>(initialState());
const loggedIn = computed(() => currentAccount.value !== null);
const missingContact = computed<ContactType | null>(() =>
  !currentAccount.value?.phone
    ? "phone"
    : !currentAccount.value?.email
      ? "email"
      : null,
);
const auth = useAuthForm({
  hasAccount: () => currentAccount.value !== null,
  missingContact: () => missingContact.value,
  signedIn: (account) => applyAccount(account),
  accountUpdated: (account) => {
    currentAccount.value = account;
  },
});

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
const groupName = ref("");
const inviteIds = ref<string[]>([]);
const knownPeers = ref<ContactProfile[]>([]);
let toastTimer: ReturnType<typeof setTimeout> | undefined;

function notify(s: string) {
  toast.value = s;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (toast.value = ""), 3500);
}

const tasks = useTasks({
  accountId: () => currentAccount.value?.id ?? null,
  activeGroupId: () => serverGroupId(activeId.value),
  openTaskPage: () => { page.value = "tasks"; },
  notify,
  revealSource: revealTaskSource,
});
const taskDraft = tasks.draft;
const taskDraftDueDate = tasks.draftDueDate;
const taskSource = tasks.source;
const editingTaskId = tasks.editingId;
const editingTaskTitle = tasks.editingTitle;
const editingTaskDueDate = tasks.editingDueDate;
const taskSaving = tasks.saving;
const refreshTasks = tasks.refresh;
const addTask = tasks.add;
const stageTaskFromMessage = tasks.stageFromMessage;

const contacts = useContacts({
  accountId: () => currentAccount.value?.id ?? null,
  rememberPeers: (profiles) => {
    const merged = new Map(knownPeers.value.map((p) => [p.id, p]));
    for (const profile of profiles) merged.set(profile.id, profile);
    knownPeers.value = [...merged.values()];
  },
  showRequests: () => {
    modal.value = null;
    page.value = "requests";
  },
  notify: (message) => notify(message),
});
const contactProfiles = contacts.items;
const contactRequests = contacts.requests;
const friendsNextCursor = contacts.friendsCursor;
const contactsBusy = contacts.busy;
const friendSearchType = contacts.searchType;
const friendQuery = contacts.query;
const friendNote = contacts.note;
const friendError = contacts.inviteError;
const friendBusy = contacts.inviteBusy;
const refreshContacts = contacts.refresh;
const loadMoreFriends = contacts.loadMoreFriends;
const requestFriend = contacts.invite;
const groupBusy = ref(false);

const summaries = useGroupSummaries(
  () => serverGroupId(activeId.value),
  () => currentAccount.value?.id ?? null,
  showSummary,
);
const loadGroupSummaries = summaries.load;
const agent = useGroupAgent(
  () => serverGroupId(activeId.value),
  () => currentAccount.value?.id ?? null,
  () => activeId.value,
);
const notices = useNotices({
  accountId: () => currentAccount.value?.id ?? null,
  focusWords: () => data.value.preferences.focusWords,
  setFocusWords: (value: string) => { data.value.preferences.focusWords = value; },
  notify,
});
const mentionNotices = notices.items;
const noticeUnreadCount = notices.unreadCount;
const noticeError = notices.error;
const refreshNotices = notices.refresh;
const loadFocusWords = notices.loadFocusWords;

/** Conversation on screen resolved to a peer or a group; shared by history and context. */
function conversationScope() {
  return {
    peerId: directPeer(activeId.value),
    groupId: serverGroupId(activeId.value),
    conversationId: activeId.value,
  };
}

const history = useHistorySearch({
  accountId: () => currentAccount.value?.id ?? null,
  scope: conversationScope,
});
const historySearchOpen = history.open;
const historyQuery = history.query;
const historyTerm = history.term;
const historyResults = history.results;
const historyNextBeforeId = history.nextBeforeId;
const historySearching = history.searching;
const historySearchError = history.error;
const searchHistory = history.search;
const closeHistorySearch = history.close;

const messageList = ref<{ scroller: HTMLElement | null } | null>(null);
/** The transcript owns the element; the shell only ever reads it for scroll anchoring. */
const messageScroll = computed(() => messageList.value?.scroller ?? null);
const showOlder = ref(false);
const focusId = ref("");

const feed = useChatFeed({
  accountId: () => currentAccount.value?.id ?? null,
  activeId: () => activeId.value,
  page: () => page.value,
  contextActive: () => context.active.value,
  notify,
  scrollBottom,
  focusId: () => focusId.value,
  hasPeer: (peerId) => contactProfiles.value.some((profile) => profile.id === peerId),
  rememberPeer: async (peerId) => {
    const accountId = currentAccount.value?.id;
    const profile = await friendProfile(peerId);
    if (currentAccount.value?.id !== accountId) return;
    contacts.remember(profile);
    if (!knownPeers.value.some((p) => p.id === profile.id))
      knownPeers.value = [...knownPeers.value, profile];
  },
  recalledInView: redactInView,
  groupRecallApplied: (messageId) => {
    agent.invalidateOnRecall();
    translation.onRecalled(messageId);
    summaries.invalidate();
  },
  groupRecallsSynced: () => { if (showSummary.value) void loadGroupSummaries(); },
  groupRemoved: (groupId) => {
    notices.discard();
    mentionNotices.value = mentionNotices.value.filter((notice) => notice.groupId !== groupId);
    if (serverGroupId(activeId.value) === groupId) {
      agent.reset();
      summaries.reset();
      closeHistorySearch();
      closeMessageContext(false);
      stopVoice();
      clearTranslationPreview();
      infoOpen.value = false;
      activeId.value = "";
      outbox.clearPending();
    }
    void refreshNotices();
  },
  conversationDeleted: () => {
    closeHistorySearch();
    closeMessageContext(false);
    activeId.value = "";
    outbox.clearPending();
  },
});
const directMessages = feed.directMessages;
const peerLastReadId = feed.peerLastReadId;
const groupMessages = feed.groupMessages;
const serverGroups = feed.serverGroups;
const groupsNextCursor = feed.groupsNextCursor;
const directUnread = feed.directUnread;
const hiddenPeers = feed.hiddenPeers;
const chatBusy = feed.chatBusy;
const chatError = feed.chatError;
const nextBeforeId = feed.nextBeforeId;
const groupNextBeforeId = feed.groupNextBeforeId;
const loadMoreGroups = feed.loadMoreGroups;

const context = useMessageContext({
  accountId: () => currentAccount.value?.id ?? null,
  scope: conversationScope,
  focusOn: (message) => {
    focusId.value = message ? renderMessage(message).id : "";
    if (!message) return;
    const elementId = `msg-${focusId.value}`;
    void nextTick(() =>
      document.getElementById(elementId)?.scrollIntoView({ behavior: "smooth", block: "center" }));
  },
  showLatest: () => {
    void nextTick(() => {
      scrollBottom();
      const { peerId, groupId } = conversationScope();
      if (peerId) void feed.markVisibleRead(peerId);
      if (groupId) void feed.markVisibleGroupRead(groupId);
    });
  },
  beforeOpen: () => {
    closeHistorySearch();
    stopVoice();
  },
  recalledAt: (message) => feed.recalledAt(message),
  scrollEl: () => messageScroll.value,
  notify: (message) => notify(message),
});
const contextActive = context.active;
const contextTarget = context.target;
const contextMessages = context.messages;
const contextBeforeId = context.beforeId;
const contextAfterId = context.afterId;
const contextBusy = context.busy;
const contextError = context.error;
const openMessageContext = context.open;
const loadMessageContext = context.load;
const closeMessageContext = context.close;

const translation = useGroupTranslation({
  accountId: () => currentAccount.value?.id ?? null,
  activeId: () => activeId.value,
  target: () => data.value.preferences.translationTarget,
  canWrite: () => canWrite.value,
  contextActive: () => contextActive.value,
  composer,
  notify,
});
const translationPreview = translation.preview;
const translationDraft = translation.draft;
const translationFor = translation.forId;
const translationBusy = translation.busy;
const translationError = translation.error;
const clearTranslationPreview = translation.clear;
const previewGroupTranslation = translation.request;
const useTranslationPreview = translation.useDraft;

const voice = useVoiceInput({
  page: () => page.value,
  canWrite: () => canWrite.value,
  composer,
  notify,
});
const voiceActive = voice.active;
const interim = voice.interim;
const voiceDraft = voice.draft;
const voiceBusy = voice.busy;
const voiceInstallAvailable = voice.installAvailable;
const stopVoice = voice.stop;
const toggleVoice = voice.toggle;
const installVoicePack = voice.installPack;
const acceptVoiceDraft = voice.accept;

const outbox = useChatSend({
  accountId: () => currentAccount.value?.id ?? null,
  activeId: () => activeId.value,
  canWrite: () => canWrite.value,
  contextActive: () => contextActive.value,
  composer,
  mentionSelections,
  voiceDraft,
  error: chatError,
  mergeDirect: feed.mergeDirect,
  mergeGroup: feed.mergeGroup,
  applyDirectRecall: feed.applyDirectRecall,
  applyGroupRecall: feed.applyGroupRecall,
  refreshSummaries: () => { if (showSummary.value) void loadGroupSummaries(); },
  stopVoice,
  scrollBottom,
  refreshHidden: () => void feed.refreshHiddenConversations(),
  notify,
});
const sending = outbox.sending;
const recallingMessageId = outbox.recallingId;
const send = outbox.send;
const shareGroupCaption = outbox.sendCaption;
const recallVisibleMessage = outbox.recall;
const canRecallMessage = outbox.canRecall;

const realtime = useRealtime({
  accountId: () => currentAccount.value?.id ?? null,
  activeId: () => activeId.value,
  page: () => page.value,
  refreshUnread: () => void feed.refreshUnread(),
  refreshHidden: () => void feed.refreshHiddenConversations(),
  refreshGroups: () => feed.refreshGroups(),
  refreshNotices: () => void refreshNotices(),
  reconcileMembership: (groupId) => feed.reconcileGroupMembership(groupId),
  requestDirect: () => feed.requestDirectRefresh(),
  requestGroup: () => void feed.refreshGroupChat(),
  requestReceipt: (peerId) => feed.requestReceipt(peerId),
  pauseVoice: () => stopVoice(),
});

const nav: { id: Page; label: string; icon: string }[] = [
  { id: "chat", label: "消息", icon: "message" },
  { id: "contacts", label: "联系人", icon: "users" },
  { id: "requests", label: "好友申请", icon: "userPlus" },
  { id: "notices", label: "通知", icon: "bell" },
  { id: "tasks", label: "我的待办", icon: "checkSquare" },
  { id: "settings", label: "设置", icon: "settings" },
];

const view = useConversationView({
  accountId: () => currentAccount.value?.id ?? null,
  activeId: () => activeId.value,
  search,
  range,
  serverGroups,
  directMessages,
  groupMessages,
  peerLastReadId,
  directUnread,
  hiddenPeers,
  knownPeers,
  contactProfiles,
  contactRequests,
  contextActive,
  contextMessages,
  noticeUnread: noticeUnreadCount,
  people: () => data.value.people,
  friendIds: () => data.value.friendIds,
  focusWords: () => data.value.preferences.focusWords,
});
const realGroups = view.realGroups;
const allConversations = view.allConversations;
const active = view.active;
const isDirectChat = view.isDirectChat;
const isGroupChat = view.isGroupChat;
const mentionIds = view.mentionIds;
const person = view.person;
const renderMessage = view.renderMessage;
const messages = view.messages;
const visibleMessages = view.visibleMessages;
const conversations = view.conversations;
const friends = view.friends;
const received = view.received;
const sent = view.sent;
const unread = view.unread;
const canWrite = view.canWrite;
const candidates = view.candidates;
const focusCount = view.focusCount;
const summarySourcesView = view.summarySourcesView;

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
watch(allConversations, (items) => {
  if (currentAccount.value && (!activeId.value || !items.some((c) => c.id === activeId.value)))
    activeId.value = items[0]?.id || "";
});
watch(activeId, () => {
  agent.reset();
  clearTranslationPreview();
  closeHistorySearch();
  closeMessageContext(false);
  mentionPickerOpen.value = false;
  mentionSelections.value = {};
  showSummary.value = false;
  summaries.reset();
  showOlder.value = false;
  stopVoice();
  feed.resetConversationState();
  feed.loadActive();
  nextTick(scrollBottom);
});
watch(page, () => {
  if (page.value !== "chat") { closeHistorySearch(); closeMessageContext(false); }
  stopVoice();
  infoOpen.value = false;
  if (page.value !== "chat") mobileChatOpen.value = false;
  if (page.value === "contacts" || page.value === "requests")
    void refreshContacts();
  if (page.value === "notices") void refreshNotices();
  if (page.value === "tasks") void refreshTasks();
  if (page.value === "chat") feed.loadActive();
});
watch(canWrite, (allowed) => { if (!allowed) stopVoice(); });

function scrollBottom() {
  if (!contextActive.value && messageScroll.value)
    messageScroll.value.scrollTop = messageScroll.value.scrollHeight;
}
/** Redact the same body in the search hits and the excerpt the feed already redacted. */
function redactInView(recall: MessageRecall, kind: "direct" | "group") {
  const matches = (m: DirectMessage | GroupMessage) =>
    m.id === recall.messageId && ("groupId" in m) === (kind === "group");
  const redact = (m: DirectMessage | GroupMessage) =>
    matches(m) ? { ...m, body: "", recalledAt: recall.recalledAt } : m;
  contextMessages.value = contextMessages.value.map(redact);
  if (contextTarget.value && matches(contextTarget.value))
    contextTarget.value = redact(contextTarget.value);
  historyResults.value = historyResults.value.filter((m) => !matches(m));
}
function openChat(id: string, source?: string) {
  closeMessageContext(false);
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
  if (wasActive && !chatBusy.value) feed.loadActive();
}
function openFriendChat(p: Person) {
  openChat(`server-${p.id}`);
}
function deleteCurrentConversation() {
  const peerId = directPeer(activeId.value);
  if (!peerId || !active.value) return;
  void feed.deleteConversation(peerId, active.value.name);
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
const groupAdmin = useGroupAdmin({
  activeId: () => activeId.value,
  active: () => active.value,
  serverGroups,
  inviteIds,
  groupName,
  busy: groupBusy,
  error: friendError,
  notify,
  openChat,
  closeModal: () => (modal.value = null),
  forgetGroup: (groupId) => feed.forgetGroup(groupId),
  nameOf: (personId) => person(personId).name,
});
const createGroup = groupAdmin.create;
const addMembers = groupAdmin.addMembers;
const leaveActiveGroup = groupAdmin.leave;
const removeActiveGroupMember = groupAdmin.removeMember;
const transferActiveGroupOwner = groupAdmin.transferOwner;
const dissolveActiveGroup = groupAdmin.dissolve;
/** Open a notice's message in its discussion room. The list state lives in useNotices. */
async function openNotice(n: MentionNotice) {
  try {
    const id = `server-group-${n.groupId}`;
    if (!serverGroups.value.some((g) => g.id === n.groupId)) await feed.refreshGroups();
    if (!serverGroups.value.some((g) => g.id === n.groupId)) throw new Error("无法找到此讨论室");
    const target = await getGroupMessage(n.groupId, n.messageId);
    await notices.readOne(n);
    openChat(id);
    if (activeId.value !== id) return;
    await nextTick();
    feed.mergeGroup([target]);
    focusId.value = `group-message-${target.id}`;
    await nextTick();
    document.getElementById(`msg-${focusId.value}`)?.scrollIntoView({ behavior: "smooth", block: "center" });
    setTimeout(() => (focusId.value = ""), 3000);
  } catch (error) {
    noticeError.value = error instanceof Error ? error.message : "打开通知失败";
    notify(noticeError.value);
  }
}
/** Navigation for a task's source message. Task error UI stays in useTasks. */
async function revealTaskSource(groupId: number, messageId: number) {
  const accountId = currentAccount.value?.id;
  const target = await getGroupMessage(groupId, messageId);
  if (currentAccount.value?.id !== accountId) return;
  if (target.recalledAt) throw new Error("来源消息已撤回");
  const id = `server-group-${groupId}`;
  if (!serverGroups.value.some((group) => group.id === groupId)) {
    const group = await getGroup(groupId);
    if (currentAccount.value?.id !== accountId) return;
    serverGroups.value = [...serverGroups.value, group].sort((a, b) => b.id - a.id);
  }
  openChat(id);
  if (activeId.value !== id) return;
  await nextTick();
  await openMessageContext(target);
}
const session = useAccountSession({
  account: currentAccount,
  data,
  knownPeers,
  activeId,
  page,
  mentionSelections,
  auth,
  modules: {
    agent,
    translation,
    voice,
    realtime,
    feed,
    outbox,
    contacts,
    summaries,
    tasks,
    notices,
    historySearch: history,
    messageContext: context,
  },
  loadFocusWords,
  firstConversationId: () => allConversations.value[0]?.id || "",
  scrollBottom,
  notify,
});
const authRestoring = session.restoring;
const applyAccount = session.apply;
const signOut = session.signOut;
const reset = session.resetDemo;

const stopSessionEvents = onSessionExpired(() => session.handleExpiry());
onMounted(() => void session.restore());
onBeforeUnmount(() => {
  stopSessionEvents();
  stopVoice();
  closeHistorySearch();
  closeMessageContext(false);
  realtime.dispose();
  clearTimeout(toastTimer);
  auth.dispose();
});
function mentionMember(id: string) {
  const memberId = Number(id.slice(5));
  const token = `@${person(id).name}#${memberId}`;
  mentionSelections.value[memberId] = token;
  composer.value += `${composer.value && !/\s$/.test(composer.value) ? " " : ""}${token} `;
  mentionPickerOpen.value = false;
}
/**
 * One typed bag instead of forty props: the transcript reads nearly every piece of
 * chat state, and a single object keeps the wiring auditable in one place.
 */
const transcript = {
  messages: visibleMessages,
  chatBusy,
  chatError,
  canWrite,
  isDirect: isDirectChat,
  isGroup: isGroupChat,
  contextActive,
  contextMessages,
  contextTarget,
  contextBeforeId,
  contextAfterId,
  contextBusy,
  contextError,
  nextBeforeId,
  groupNextBeforeId,
  unreadMarker,
  focusId,
  showTranslations: computed(() => data.value.preferences.showTranslations),
  translationFor,
  translationBusy,
  translationError,
  translationPreview,
  recallingMessageId,
  person,
  canRecall: canRecallMessage,
  onLoadOlderDirect: () => void feed.loadOlderDirect(),
  onLoadOlderGroup: () => void feed.loadOlderGroup(),
  onRetryChat: () => (directPeer(activeId.value) ? feed.refreshDirectChat() : feed.refreshGroupChat()),
  onLoadContext: (direction: "older" | "newer") => void loadMessageContext(direction),
  onOpenContext: (target: Parameters<typeof openMessageContext>[0]) => void openMessageContext(target),
  onRecall: (m: Message) => void recallVisibleMessage(m),
  onStageTask: stageTaskFromMessage,
  onTranslate: previewGroupTranslation,
  onUseTranslation: useTranslationPreview,
  onClearTranslation: clearTranslationPreview,
};
</script>

<template>
  <AuthShell v-if="authRestoring || !loggedIn" :restoring="authRestoring" :auth="auth" />
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
      <ConversationPanel
        v-model:search="search"
        :conversations="conversations"
        :total-count="allConversations.length"
        :active-id="activeId"
        :active-preview="active ? messages.at(-1) ?? null : null"
        :has-more-groups="Boolean(groupsNextCursor)"
        @open="openChat"
        @create-group="openModal('group')"
        @load-more-groups="loadMoreGroups"
      />
      <main
        v-if="active"
        :class="['chat-main', { 'mobile-open': mobileChatOpen }]"
      >
        <ChatHeader
          v-model:history-open="historySearchOpen"
          v-model:history-query="historyQuery"
          :active="active"
          :is-direct="isDirectChat"
          :is-group="isGroupChat"
          :can-write="canWrite"
          :self-account-id="currentAccount?.id ?? null"
          :context-active="contextActive"
          :assistant-open="assistantOpen"
          :history-term="historyTerm"
          :history-results="historyResults"
          :history-searching="historySearching"
          :history-search-error="historySearchError"
          :history-next-before-id="historyNextBeforeId"
          :person="person"
          @back="mobileChatOpen = false"
          @delete-conversation="deleteCurrentConversation"
          @search="searchHistory"
          @close-history="closeHistorySearch"
          @open-context="openMessageContext"
          @toggle-assistant="assistantOpen = !assistantOpen"
          @open-info="infoOpen = true"
          @close-context="closeMessageContext()"
        />
        <MessageList
          ref="messageList"
          v-model:translation-draft="translationDraft"
          :ctx="transcript"
        />
        <MessageComposer
          v-model:composer="composer"
          v-model:mention-picker-open="mentionPickerOpen"
          :voice-active="voiceActive"
          :voice-busy="voiceBusy"
          :voice-draft="voiceDraft"
          :voice-install-available="voiceInstallAvailable"
          :interim="interim"
          :sending="sending"
          :context-active="contextActive"
          :can-write="canWrite"
          :is-group="isGroupChat"
          :mention-ids="mentionIds"
          :person="person"
          @toggle-voice="toggleVoice"
          @stop-voice="stopVoice"
          @accept-voice-draft="acceptVoiceDraft"
          @install-voice-pack="installVoicePack"
          @share-caption="shareGroupCaption"
          @mention="mentionMember"
          @send="send"
        />
      </main>
      <AssistantPanel
        v-model:show-summary="showSummary"
        v-model:range="range"
        v-model:task-draft="taskDraft"
        :open="assistantOpen"
        :is-group="Boolean(serverGroupId(activeId))"
        :agent="agent"
        :summaries="summaries"
        :sources="summarySourcesView"
        :focus-count="focusCount"
        :task-saving="taskSaving"
        @close="assistantOpen = false"
        @add-task="addTask"
        @open-source="openChat($event.conversationId, $event.messageId)"
        @manage-focus-words="
          page = 'settings';
          assistantOpen = false;
        "
      />
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
      <ContactsPage
        v-if="page === 'contacts'"
        :contacts="contacts"
        :friends="friends"
        :group-count="realGroups.length"
        @open-chat="openFriendChat"
      />
      <RequestsPage
        v-if="page === 'requests'"
        :contacts="contacts"
        :received="received"
        :sent="sent"
      />
      <NoticesPage
        v-if="page === 'notices'"
        :notices="notices"
        :items="mentionNotices"
        :unread="unread"
        :person="person"
        @open="openNotice"
      />
      <TasksPage
        v-if="page === 'tasks'"
        v-model:draft="taskDraft"
        v-model:draft-due-date="taskDraftDueDate"
        v-model:source="taskSource"
        v-model:editing-id="editingTaskId"
        v-model:editing-title="editingTaskTitle"
        v-model:editing-due-date="editingTaskDueDate"
        :tasks="tasks"
      />
      <SettingsPage
        v-if="page === 'settings'"
        :data="data"
        :account="currentAccount"
        :missing-contact="missingContact"
        :auth="auth"
        :notices="notices"
        @sign-out="signOut"
        @reset-demo="reset"
      />
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
    <ChatInfoDrawer
      v-if="active"
      v-model:open="infoOpen"
      :active="active"
      :group-busy="groupBusy"
      :person="person"
      @invite="openModal('invite')"
      @transfer-owner="transferActiveGroupOwner"
      @remove-member="removeActiveGroupMember"
      @leave="leaveActiveGroup"
      @dissolve="dissolveActiveGroup"
    />
    <AppModals
      v-model:modal="modal"
      v-model:friend-search-type="friendSearchType"
      v-model:friend-query="friendQuery"
      v-model:friend-note="friendNote"
      v-model:friend-error="friendError"
      v-model:group-name="groupName"
      v-model:invite-ids="inviteIds"
      :candidates="candidates"
      :friends="friends"
      :active="active ?? null"
      :contacts-busy="contactsBusy"
      :group-busy="groupBusy"
      :friend-busy="friendBusy"
      :has-more-friends="Boolean(friendsNextCursor)"
      @request-friend="requestFriend"
      @toggle-invite="toggleInvite"
      @load-more-friends="loadMoreFriends"
      @confirm="(kind) => (kind === 'group' ? createGroup() : addMembers())"
    />
    <div v-if="toast" class="toast" role="status">
      <AppIcon name="info" :size="17" />{{ toast }}
    </div>
  </div>
</template>
