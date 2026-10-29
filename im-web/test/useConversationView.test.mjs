import test from "node:test";
import assert from "node:assert/strict";
import { ref } from "vue";
import { useConversationView } from "../src/useConversationView.ts";

const profile = (id, nickname) => ({ id, nickname, handle: `user${id}` });
const group = (id, name, memberIds, unreadCount = 0) => ({
  id, name, ownerId: 7, memberIds, unreadCount,
});
const direct = (id, senderId, body, extra = {}) => ({
  id, peerId: 3, senderId, clientMsgId: `c${id}`, body, kind: "TEXT",
  createdAt: "2026-10-29T10:00:00Z", recalledAt: null, ...extra,
});
const groupMessage = (id, senderId, body, extra = {}) => ({
  id, groupId: 9, senderId, clientMsgId: `g${id}`, body, kind: "TEXT",
  createdAt: "2026-10-29T10:05:00Z", recalledAt: null, ...extra,
});

function state(overrides = {}) {
  const base = {
    accountId: () => 7,
    activeId: () => "server-user-3",
    search: ref(""),
    range: ref("recent"),
    serverGroups: ref([group(9, "设计组", [7, 3, 11], 2)]),
    directMessages: ref([]),
    groupMessages: ref([]),
    peerLastReadId: ref(0),
    directUnread: ref({ 3: 2 }),
    hiddenPeers: ref([]),
    knownPeers: ref([profile(3, "张三"), profile(11, "李四")]),
    contactProfiles: ref([profile(3, "张三"), profile(11, "李四")]),
    contactRequests: ref([]),
    contextActive: ref(false),
    contextMessages: ref([]),
    noticeUnread: ref(0),
    people: () => [{ id: "me", name: "我", handle: "me", initials: "我", color: "violet", status: "online", note: "" }],
    friendIds: () => [],
    focusWords: () => "",
  };
  return { ...base, ...overrides };
}

test("rooms and peers are mapped to the shell's id conventions", () => {
  const view = useConversationView(state());

  assert.deepEqual(view.allConversations.value.map((c) => c.id), [
    "server-group-9", "server-user-3", "server-user-11",
  ]);
  const room = view.allConversations.value[0];
  assert.equal(room.ownerId, "me");
  assert.deepEqual(room.memberIds, ["me", "user-3", "user-11"]);
  assert.equal(room.unread, 2);
  assert.equal(view.active.value.name, "张三");
  // Mentions come from the active conversation, which here is the direct chat.
  assert.deepEqual(view.mentionIds.value, ["user-3"]);
});

test("a hidden peer stays in the list while its conversation is open", () => {
  const hiddenPeers = ref([3, 11]);
  const knownPeers = ref([profile(3, "张三"), profile(11, "李四")]);
  const closed = useConversationView(state({ hiddenPeers, knownPeers, activeId: () => "server-user-3" }));
  assert.deepEqual(closed.allConversations.value.map((c) => c.id), ["server-group-9", "server-user-3"]);

  const elsewhere = useConversationView(state({ hiddenPeers, knownPeers, activeId: () => "" }));
  assert.deepEqual(elsewhere.allConversations.value.map((c) => c.id), ["server-group-9"]);
});

test("recalls and captions decide the rendered body, kind and receipt", () => {
  const peerLastReadId = ref(3);
  const view = useConversationView(state({
    peerLastReadId,
    directMessages: ref([
      direct(3, 7, "我发的", {}),
      direct(4, 7, "已被读过", {}),
      direct(5, 7, "撤回的", { recalledAt: "2026-10-29T10:01:00Z" }),
      direct(6, 3, "对方的", {}),
    ]),
  }));
  const [acknowledged, pending, recalled, theirs] = view.messages.value;

  assert.equal(acknowledged.readReceipt, "对方已读");
  assert.equal(pending.readReceipt, "已发送");
  assert.equal(recalled.text, "消息已撤回");
  assert.equal(recalled.recalled, true);
  assert.equal(recalled.readReceipt, undefined);
  assert.equal(theirs.readReceipt, undefined);
  assert.equal(theirs.senderId, "user-3");
});

test("a caption renders as voice while the group is the active room", () => {
  const view = useConversationView(state({
    activeId: () => "server-group-9",
    groupMessages: ref([
      groupMessage(1, 3, "字幕内容", { kind: "CAPTION" }),
      groupMessage(2, 3, "普通消息", { kind: "TEXT" }),
    ]),
  }));

  assert.deepEqual(view.messages.value.map((m) => m.kind), ["voice", "text"]);
  assert.equal(view.messages.value[0].conversationId, "server-group-9");
  assert.equal(view.isGroupChat.value, true);
  assert.equal(view.isDirectChat.value, false);
});

test("the context slice replaces the transcript while it is open", () => {
  const contextActive = ref(true);
  const contextMessages = ref([groupMessage(7, 3, "上下文里的")]);
  const view = useConversationView(state({
    contextActive,
    contextMessages,
    activeId: () => "server-group-9",
    groupMessages: ref([groupMessage(1, 3, "末尾的")]),
  }));

  assert.deepEqual(view.visibleMessages.value.map((m) => m.text), ["上下文里的"]);
  contextActive.value = false;
  assert.deepEqual(view.visibleMessages.value.map((m) => m.text), ["末尾的"]);
});

test("a cached sender still gets a row without being on the friend page", () => {
  const view = useConversationView(state({
    knownPeers: ref([profile(3, "张三"), profile(42, "只在未读里出现")]),
    contactProfiles: ref([profile(3, "张三")]),
  }));

  assert.deepEqual(view.allConversations.value.map((c) => c.id), [
    "server-group-9", "server-user-3", "server-user-42",
  ]);
  assert.deepEqual(view.friends.value.map((f) => f.id), ["user-3"]);
});

test("typing narrows conversations but never the transcript", () => {
  const search = ref("李");
  const view = useConversationView(state({ search }));

  assert.deepEqual(view.conversations.value.map((c) => c.id), ["server-user-11"]);
  assert.equal(view.messages.value.length, 0);
});

test("a peer the friend list dropped is read-only", () => {
  const view = useConversationView(state());
  assert.equal(view.canWrite.value, true);

  const stranger = useConversationView(state({
    activeId: () => "server-user-99",
    contactProfiles: ref([profile(3, "张三")]),
  }));
  assert.equal(stranger.canWrite.value, false);
});

test("focus words count only other people's live messages", () => {
  const focusWords = ref("上线，发布");
  const view = useConversationView(state({
    focusWords: () => focusWords.value,
    activeId: () => "server-group-9",
    groupMessages: ref([
      groupMessage(1, 3, "今天上线"),
      groupMessage(2, 11, "发布计划"),
      groupMessage(3, 7, "我说明天上线"),
      groupMessage(4, 11, "撤回的发布", { recalledAt: "2026-10-29T10:06:00Z" }),
    ]),
  }));

  assert.equal(view.focusCount.value, 2);
});

test("summary sources keep the last three other-person lines and name the sender", () => {
  const view = useConversationView(state({
    activeId: () => "server-group-9",
    groupMessages: ref([
      groupMessage(1, 3, "一句话"),
      groupMessage(2, 7, "我自己说的"),
      groupMessage(3, 11, "第二句话"),
      groupMessage(4, 3, "第三句话"),
      groupMessage(5, 11, "长".repeat(50)),
    ]),
  }));

  assert.deepEqual(view.summarySourcesView.value.map((s) => s.id), [
    "group-message-3", "group-message-4", "group-message-5",
  ]);
  assert.match(view.summarySourcesView.value[0].label, /^李四：第二句话$/);
  assert.equal(view.summarySourcesView.value[2].label, `李四：${"长".repeat(38)}…`);

  const longer = useConversationView(state({
    range: ref("all"),
    activeId: () => "server-group-9",
    groupMessages: ref([
      groupMessage(1, 3, "第一句"),
      groupMessage(2, 3, "第二句"),
      groupMessage(3, 3, "第三句"),
      groupMessage(4, 3, "第四句"),
    ]),
  }));
  assert.equal(longer.summarySourcesView.value.length, 3);
  assert.match(longer.summarySourcesView.value[2].label, /第四句/);
});
