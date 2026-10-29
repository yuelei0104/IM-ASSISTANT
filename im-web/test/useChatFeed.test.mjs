import test from "node:test";
import assert from "node:assert/strict";
import { sessionKey } from "../src/session.ts";
import { useChatFeed } from "../src/useChatFeed.ts";

function login() {
  const store = new Map([[sessionKey, "token"]]);
  globalThis.sessionStorage = {
    getItem: (key) => store.get(key) ?? null,
    setItem: (key, value) => store.set(key, value),
    removeItem: (key) => store.delete(key),
  };
}

function dom(hidden = false) {
  const listeners = new Map();
  globalThis.document = {
    hidden,
    addEventListener: (type, handler) => listeners.set(type, handler),
    removeEventListener: (type) => listeners.delete(type),
    getElementById: () => null,
  };
  return listeners;
}

function deps(overrides = {}) {
  const state = {
    notes: [], reminders: [], recalls: [], recalcs: [], removed: [], deleted: 0, scrolls: 0,
  };
  return {
    state,
    accountId: () => 7,
    activeId: () => "server-user-3",
    page: () => "chat",
    contextActive: () => false,
    notify: (message) => state.notes.push(message),
    scrollBottom: () => state.scrolls++,
    focusId: () => "",
    hasPeer: () => false,
    rememberPeer: async (peerId) => state.reminders.push(peerId),
    recalledInView: (recall, kind) => state.recalls.push([kind, recall.messageId]),
    groupRecallApplied: (messageId) => state.recalcs.push(messageId),
    groupRecallsSynced: () => {},
    groupRemoved: (groupId) => state.removed.push(groupId),
    conversationDeleted: () => state.deleted++,
    ...overrides,
  };
}

const dm = (id, body = `消息 ${id}`, senderId = 3) => ({
  id, senderId, recipientId: 7, clientMsgId: `c-${id}`, body,
  createdAt: "2026-10-29T10:00:00Z", recalledAt: null,
});

const gm = (id, body = `群消息 ${id}`, senderId = 3) => ({
  id, groupId: 5, senderId, clientMsgId: `g-${id}`, body, kind: "TEXT",
  createdAt: "2026-10-29T10:00:00Z", recalledAt: null,
});

const group = (id, name = "发布讨论") => ({
  id, name, ownerId: 7, memberIds: [7, 3], unreadCount: 2,
});

const tick = () => new Promise((resolve) => setTimeout(resolve, 0));

test("the newest page is merged with the incremental tail and kept in order", async () => {
  login();
  dom();
  globalThis.fetch = async (url) => {
    const target = String(url);
    if (target.includes("/messages/sync"))
      return Response.json({ messages: [dm(12)], nextAfterId: null });
    if (target.includes("/messages/recalls"))
      return Response.json({ items: [], nextAfterId: null });
    if (target.includes("/receipt")) return Response.json({ lastReadMessageId: 11 });
    if (target.includes("/read")) return Response.json({});
    if (target.includes("/unread")) return Response.json([]);
    if (target.includes("/messages"))
      return Response.json({ messages: [dm(10), dm(11)], nextBeforeId: 9 });
    return Response.json({});
  };
  const d = deps();
  const feed = useChatFeed(d);
  await feed.refreshDirectChat();
  await tick();

  assert.deepEqual(feed.directMessages.value.map((m) => m.id), [10, 11, 12]);
  assert.equal(feed.nextBeforeId.value, 9);
  assert.equal(feed.chatBusy.value, false);
  assert.equal(feed.chatError.value, "");
  assert.equal(feed.peerLastReadId.value, 11, "the durable receipt arrives out of band");
  assert.equal(d.state.scrolls, 1);
});

test("a recall from another device cannot be resurrected by a later page", () => {
  const d = deps();
  const feed = useChatFeed(d);
  feed.mergeDirect([dm(4, "原话"), dm(5)]);
  feed.applyDirectRecall({ id: 1, messageId: 4, recalledAt: "2026-10-29T11:00:00Z" });
  feed.mergeDirect([dm(4, "原话"), dm(6)]);

  assert.deepEqual(feed.directMessages.value.map((m) => [m.id, m.body]),
    [[4, ""], [5, "消息 5"], [6, "消息 6"]]);
  assert.deepEqual(d.state.recalls, [["direct", 4]]);
  assert.equal(feed.recalledAt(dm(4)), "2026-10-29T11:00:00Z");
});

test("a group recall invalidates the summary and redacts the group thread only", () => {
  const d = deps();
  const feed = useChatFeed(d);
  feed.mergeDirect([dm(4)]);
  feed.mergeGroup([gm(4, "群里的原话")]);
  feed.applyGroupRecall({ id: 2, messageId: 4, recalledAt: "2026-10-29T12:00:00Z" });

  assert.equal(feed.groupMessages.value[0].body, "");
  assert.equal(feed.directMessages.value[0].body, "消息 4");
  assert.deepEqual(d.state.recalls, [["group", 4]]);
  assert.deepEqual(d.state.recalcs, [4], "the summary and any translation of it are stale now");
});

test("unread counts only resolve senders the friend list does not know yet", async () => {
  login();
  dom();
  globalThis.fetch = async (url) => {
    const target = String(url);
    if (target.includes("/unread"))
      return Response.json([{ peerId: 9, unreadCount: 2 }, { peerId: 8, unreadCount: 1 }]);
    return Response.json({});
  };
  const d = deps({ hasPeer: (peerId) => peerId === 8 });
  const feed = useChatFeed(d);
  await feed.refreshUnread();
  await tick();

  assert.deepEqual(feed.directUnread.value, { 9: 2, 8: 1 });
  assert.deepEqual(d.state.reminders, [9]);
});

test("a 403 on a group thread drops the room and tells the shell", async () => {
  login();
  dom();
  globalThis.fetch = async (url) => {
    const target = String(url);
    if (target.includes("/chats/groups?limit=50"))
      return Response.json({ items: [group(5)], nextBeforeId: null });
    if (target.includes("/chats/groups/5/messages"))
      return new Response(JSON.stringify({ message: "已不在讨论室" }), { status: 403 });
    return Response.json({});
  };
  const d = deps({ activeId: () => "server-group-5" });
  const feed = useChatFeed(d);
  await feed.refreshGroups();
  assert.equal(feed.serverGroups.value.length, 1);

  await feed.refreshGroupChat();

  assert.deepEqual(d.state.removed, [5]);
  assert.equal(feed.serverGroups.value.length, 0);
  assert.equal(feed.chatError.value, "", "leaving a room is not an error banner");
});

test("signing out clears every cached thread, cursor and counter", async () => {
  login();
  dom();
  globalThis.fetch = async (url) => {
    const target = String(url);
    if (target.includes("/chats/groups?limit=50"))
      return Response.json({ items: [group(5)], nextBeforeId: 5 });
    if (target.includes("/unread")) return Response.json([{ peerId: 9, unreadCount: 2 }]);
    if (target.includes("/conversations/hidden")) return Response.json([9]);
    return Response.json({ messages: [dm(3)], nextBeforeId: null });
  };
  const d = deps();
  const feed = useChatFeed(d);
  feed.mergeDirect([dm(3)]);
  feed.mergeGroup([gm(3)]);
  await feed.refreshUnread();
  await feed.refreshHiddenConversations();
  await feed.refreshGroups();

  feed.reset();

  assert.deepEqual(feed.directMessages.value, []);
  assert.deepEqual(feed.groupMessages.value, []);
  assert.deepEqual(feed.serverGroups.value, []);
  assert.deepEqual(feed.directUnread.value, {});
  assert.deepEqual(feed.hiddenPeers.value, []);
  assert.equal(feed.groupsNextCursor.value, null);
  assert.equal(feed.chatBusy.value, false);
});
