import { computed, type Ref } from "vue";
import type { ContactProfile, ContactRequest } from "./contactApi";
import type { DirectMessage } from "./chatApi";
import type { GroupChat, GroupMessage } from "./groupApi";
import { directPeer, serverGroupId } from "./chatIds";
import { chinaMessageTime } from "./chinaTime";
import { selfId, type Conversation, type Message, type Person } from "./demo";

type Deps = {
  accountId: () => number | null;
  activeId: () => string;
  /** Sidebar filter text. */
  search: Ref<string>;
  /** How much of the transcript the summary panel is allowed to read. */
  range: Ref<"recent" | "all">;
  serverGroups: Ref<GroupChat[]>;
  directMessages: Ref<DirectMessage[]>;
  groupMessages: Ref<GroupMessage[]>;
  peerLastReadId: Ref<number>;
  directUnread: Ref<Record<number, number>>;
  hiddenPeers: Ref<number[]>;
  /** Cached peers, not the friend page: a sender seen in an unread thread still gets a row. */
  knownPeers: Ref<ContactProfile[]>;
  contactProfiles: Ref<ContactProfile[]>;
  contactRequests: Ref<ContactRequest[]>;
  /** True while the transcript shows a slice around one message instead of the tail. */
  contextActive: Ref<boolean>;
  contextMessages: Ref<Array<DirectMessage | GroupMessage>>;
  noticeUnread: Ref<number>;
  /** Seeded demo people, used to name a sender the friend list does not cover. */
  people: () => Person[];
  friendIds: () => string[];
  focusWords: () => string;
};

/**
 * Every list and label the chat workspace derives from server state. Nothing here
 * mutates or fetches: it exists so the shell keeps only wiring, and so the id
 * conventions (`server-user-1`, `server-group-7`, `me`) are applied in one place.
 */
export function useConversationView(deps: Deps) {
  const accountId = () => deps.accountId();

  const realGroups = computed<Conversation[]>(() =>
    deps.serverGroups.value.map((g) => ({
      id: `server-group-${g.id}`,
      name: g.name,
      kind: "group",
      memberIds: g.memberIds.map((id) => (id === accountId() ? selfId : `user-${id}`)),
      ownerId: g.ownerId === accountId() ? selfId : `user-${g.ownerId}`,
      unread: g.unreadCount,
      color: "teal",
      initials: g.name[0] || "群",
    })),
  );

  const realConversations = computed<Conversation[]>(() =>
    deps.knownPeers.value
      .filter(
        (p) => !deps.hiddenPeers.value.includes(p.id) || deps.activeId() === `server-user-${p.id}`,
      )
      .map((p) => ({
        id: `server-user-${p.id}`,
        name: p.nickname,
        kind: "direct",
        memberIds: [selfId, `user-${p.id}`],
        unread: deps.directUnread.value[p.id] ?? 0,
        color: "violet",
        initials: p.nickname[0] || "友",
      })),
  );

  const allConversations = computed(() => [...realGroups.value, ...realConversations.value]);
  const active = computed(() => allConversations.value.find((c) => c.id === deps.activeId()));
  const isDirectChat = computed(() => Boolean(directPeer(deps.activeId())));
  const isGroupChat = computed(() => Boolean(serverGroupId(deps.activeId())));
  /** Group members the composer can @-mention; "me" is filtered out. */
  const mentionIds = computed(() => (active.value?.memberIds ?? []).filter((id) => id !== selfId));

  function person(id: string): Person {
    return (
      friends.value.find((p) => p.id === id) ||
      deps.people().find((p) => p.id === id) ||
      (id === selfId ? deps.people()[0] : undefined) || {
        id,
        name: `用户 ${id.replace(/^user-/, "")}`,
        handle: "",
        initials: "友",
        color: "violet",
        status: "offline",
        note: "",
      }
    );
  }

  function renderMessage(m: DirectMessage | GroupMessage): Message {
    const mine = m.senderId === accountId();
    return {
      id: `${"groupId" in m ? "group" : "server"}-message-${m.id}`,
      conversationId: deps.activeId(),
      senderId: mine ? selfId : `user-${m.senderId}`,
      text: m.recalledAt ? "消息已撤回" : m.body,
      time: chinaMessageTime(m.createdAt),
      kind: !m.recalledAt && "groupId" in m && m.kind === "CAPTION" ? "voice" : "text",
      recalled: !!m.recalledAt,
      createdAt: m.createdAt,
      readReceipt:
        !("groupId" in m) && mine && !m.recalledAt
          ? m.id <= deps.peerLastReadId.value
            ? "对方已读"
            : "已发送"
          : undefined,
    };
  }

  const messages = computed<Message[]>(() =>
    directPeer(deps.activeId())
      ? deps.directMessages.value.map(renderMessage)
      : serverGroupId(deps.activeId())
        ? deps.groupMessages.value.map(renderMessage)
        : [],
  );
  const visibleMessages = computed(() =>
    deps.contextActive.value ? deps.contextMessages.value.map(renderMessage) : messages.value,
  );
  const conversations = computed(() =>
    allConversations.value.filter((c) =>
      c.name.toLowerCase().includes(deps.search.value.toLowerCase()),
    ),
  );

  const friends = computed<Person[]>(() =>
    deps.contactProfiles.value.map((p) => ({
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
    deps.contactRequests.value.filter((r) => r.toId === accountId()),
  );
  const sent = computed(() => deps.contactRequests.value.filter((r) => r.fromId === accountId()));
  const unread = computed(() => deps.noticeUnread.value);

  /** A peer the server still knows but the friend list dropped stays read-only. */
  const canWrite = computed(
    () =>
      !!active.value &&
      (directPeer(deps.activeId())
        ? deps.contactProfiles.value.some((p) => p.id === directPeer(deps.activeId()))
        : active.value.kind === "group"
          ? active.value.memberIds.includes(selfId)
          : active.value.memberIds.every(
              (id) => id === selfId || deps.friendIds().includes(id),
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
          !m.recalled &&
          deps
            .focusWords()
            .split(/[,，]/)
            .some((w) => w.trim() && m.text.includes(w.trim())),
      ).length,
  );

  const summarySources = computed(() =>
    (deps.range.value === "recent" ? messages.value.slice(-5) : messages.value)
      .filter((m) => m.kind !== "system" && !m.recalled && m.senderId !== selfId)
      .slice(-3),
  );
  /** Same clues, resolved to display text so AssistantPanel never needs `person()`. */
  const summarySourcesView = computed(() =>
    summarySources.value.map((m) => ({
      id: m.id,
      conversationId: m.conversationId,
      label: `${person(m.senderId).name}：${m.text.length > 38 ? `${m.text.slice(0, 38)}…` : m.text}`,
    })),
  );

  return {
    realGroups,
    allConversations,
    active,
    isDirectChat,
    isGroupChat,
    mentionIds,
    person,
    renderMessage,
    messages,
    visibleMessages,
    conversations,
    friends,
    received,
    sent,
    unread,
    canWrite,
    candidates,
    focusCount,
    summarySourcesView,
  };
}
