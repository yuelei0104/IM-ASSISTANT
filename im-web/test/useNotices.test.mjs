import test from "node:test";
import assert from "node:assert/strict";
import { sessionKey } from "../src/session.ts";
import { useNotices } from "../src/useNotices.ts";

function login() {
  globalThis.sessionStorage = { getItem: (key) => (key === sessionKey ? "token" : null) };
}

function notice(id, overrides = {}) {
  return {
    id, groupId: 4, messageId: 100 + id, senderId: 2, groupName: "方案组",
    body: "看看这条", createdAt: "2026-10-29T08:00:00Z", readAt: null, kind: "MENTION",
    ...overrides,
  };
}

function deps(overrides = {}) {
  const state = { focusWords: "发版, 上线", notices: [] };
  return {
    state,
    accountId: () => 7,
    focusWords: () => state.focusWords,
    setFocusWords: (value) => { state.focusWords = value; },
    notify: (message) => state.notices.push(message),
    ...overrides,
  };
}

test("a notice refresh merges new items ahead of older pages", async () => {
  login();
  const d = deps();
  const notices = useNotices(d);
  globalThis.fetch = async () => Response.json({
    items: [notice(9), notice(8, { readAt: "2026-10-29T09:00:00Z" })],
    nextBeforeId: 8, unreadCount: 1,
  });
  await notices.refresh();

  globalThis.fetch = async () => Response.json({ items: [notice(7)], nextBeforeId: null, unreadCount: 1 });
  await notices.loadOlder();

  assert.deepEqual(notices.items.value.map((item) => item.id), [9, 8, 7]);
  assert.equal(notices.nextBeforeId.value, null);
  assert.equal(notices.unreadCount.value, 1);
});

test("marking one notice read decrements the counter only when it was unread", async () => {
  login();
  const notices = useNotices(deps());
  notices.unreadCount.value = 2;
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push(`${options.method} ${url}`);
    return new Response(null, { status: 204 });
  };

  const unread = notice(9);
  await notices.readOne(unread);
  await notices.readOne(unread);
  assert.equal(notices.unreadCount.value, 1);
  assert.equal(calls.length, 1);
  assert.match(calls[0], /\/api\/chats\/notices\/9\/read$/);
});

test("saving alert keywords normalizes commas before it sends them", async () => {
  login();
  const d = deps();
  const notices = useNotices(d);
  d.state.focusWords = "发版, 上线，回归";
  let sent;
  globalThis.fetch = async (_url, options) => {
    sent = JSON.parse(options.body);
    return Response.json({ words: ["发版", "上线", "回归"] });
  };

  await notices.saveFocusWords();

  assert.deepEqual(sent, { words: ["发版", "上线", "回归"] });
  assert.equal(d.state.focusWords, "发版，上线，回归");
  assert.equal(notices.keywordSaved.value, true);
});

test("a discarded notice list cannot be refilled by a late response", async () => {
  login();
  const notices = useNotices(deps());
  let release;
  globalThis.fetch = () => new Promise((resolve) => {
    release = () => resolve(Response.json({ items: [notice(9)], nextBeforeId: null, unreadCount: 1 }));
  });
  const pending = notices.refresh();
  notices.discard();
  release();
  await pending;

  assert.deepEqual(notices.items.value, []);
});
