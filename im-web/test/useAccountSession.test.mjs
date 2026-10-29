import test from "node:test";
import assert from "node:assert/strict";
import { nextTick, ref } from "vue";
import { useAccountSession, peerCacheKey } from "../src/useAccountSession.ts";
import { accountStateKey } from "../src/authApi.ts";
import { sessionKey } from "../src/session.ts";

const account = (id, name, handle) => ({ id, name, handle });

function storage() {
  const store = new Map();
  return {
    store,
    getItem: (key) => store.get(key) ?? null,
    setItem: (key, value) => store.set(key, value),
    removeItem: (key) => store.delete(key),
  };
}

function login() {
  const store = storage();
  globalThis.sessionStorage = store;
  store.setItem(sessionKey, "token");
  return store;
}

const demoState = (overrides = {}) => ({
  people: [{ id: "me", name: "旧名", handle: "old", initials: "旧", color: "violet", status: "online", note: "" }],
  friendIds: [],
  conversations: [],
  messages: [],
  requests: [],
  todos: [{ id: "t1", title: "旧待办", due: "", done: false }],
  notices: [],
  preferences: {
    fontSize: 16,
    focusWords: "旧词",
    quietHours: false,
    translationTarget: "zh",
    showTranslations: false,
    timezone: "Asia/Shanghai",
  },
  displayName: "旧名",
  handle: "old",
  ...overrides,
});

function modules(log) {
  const hit = (name) => () => log.push(name);
  return {
    agent: { reset: hit("agent.reset") },
    translation: { clear: hit("translation.clear") },
    voice: { stop: hit("voice.stop") },
    realtime: { start: hit("realtime.start"), stop: hit("realtime.stop") },
    feed: { reset: hit("feed.reset"), refreshAll: hit("feed.refreshAll") },
    outbox: { clearPending: hit("outbox.clearPending") },
    contacts: { reset: hit("contacts.reset"), refresh: hit("contacts.refresh") },
    summaries: { reset: hit("summaries.reset") },
    tasks: { reset: hit("tasks.reset"), refresh: hit("tasks.refresh") },
    notices: { reset: hit("notices.reset"), refresh: hit("notices.refresh") },
    historySearch: { close: hit("history.close") },
    messageContext: { close: hit("context.close") },
  };
}

function deps(overrides = {}) {
  const log = [];
  const notes = [];
  const scrolled = [];
  const base = {
    log,
    notes,
    scrolled,
    account: ref(null),
    data: ref(demoState()),
    knownPeers: ref([]),
    activeId: ref("server-user-3"),
    page: ref("notices"),
    mentionSelections: ref({ 3: "@张三#3" }),
    auth: { reset: () => log.push("auth.reset"), error: ref("") },
    modules: modules(log),
    loadFocusWords: () => log.push("focusWords.load"),
    firstConversationId: () => "server-group-7",
    scrollBottom: () => scrolled.push("bottom"),
    notify: (message) => notes.push(message),
  };
  return { ...base, ...overrides };
}

test("signing in resets every module before hydrating the new account", async () => {
  globalThis.localStorage = storage();
  globalThis.localStorage.setItem(
    accountStateKey(7),
    JSON.stringify(demoState({ displayName: "存过的名字" })),
  );
  globalThis.localStorage.setItem(
    peerCacheKey(7),
    JSON.stringify([{ id: 3, nickname: "张三", handle: "user3" }]),
  );
  const d = deps();
  const session = useAccountSession(d);
  session.apply(account(7, "岳蕾", "yuelei"));

  for (const name of [
    "agent.reset", "translation.clear", "voice.stop", "realtime.stop", "feed.reset",
    "outbox.clearPending", "contacts.reset", "summaries.reset", "tasks.reset", "notices.reset",
  ]) {
    assert.ok(d.log.includes(name), `${name} should run on sign-in`);
  }
  assert.deepEqual(d.knownPeers.value.map((p) => p.id), [3]);
  assert.equal(d.account.value.name, "岳蕾");
  assert.equal(d.page.value, "chat");
  assert.equal(d.activeId.value, "");
  assert.equal(d.data.value.displayName, "岳蕾");
  assert.equal(d.data.value.people[0].name, "岳蕾");
  assert.equal(d.data.value.people[0].initials, "岳");
  assert.equal(d.data.value.preferences.focusWords, "");
  assert.deepEqual(d.data.value.todos, []);
  assert.deepEqual(d.mentionSelections.value, {});
  assert.ok(d.log.includes("realtime.start"));
  assert.ok(d.log.includes("feed.refreshAll"));
  assert.ok(d.log.includes("focusWords.load"));
  // The scroll happens on the next tick, once the transcript has been re-rendered.
  await nextTick();
  assert.deepEqual(d.scrolled, ["bottom"]);
});

test("a new account never inherits the previous peer cache", () => {
  globalThis.localStorage = storage();
  globalThis.localStorage.setItem(
    peerCacheKey(7),
    JSON.stringify([{ id: 3, nickname: "张三", handle: "user3" }]),
  );
  globalThis.localStorage.setItem(
    peerCacheKey(8),
    JSON.stringify([{ id: 9, nickname: "王五", handle: "user9" }]),
  );
  const d = deps();
  const session = useAccountSession(d);

  session.apply(account(7, "岳蕾", "yuelei"));
  assert.deepEqual(d.knownPeers.value.map((p) => p.id), [3]);

  session.apply(account(8, "李四", "lisi"));
  assert.deepEqual(d.knownPeers.value.map((p) => p.id), [9]);
});

test("a corrupt peer cache is ignored instead of breaking the sign-in", () => {
  globalThis.localStorage = storage();
  globalThis.localStorage.setItem(peerCacheKey(7), "{not json");
  const d = deps();
  useAccountSession(d).apply(account(7, "岳蕾", "yuelei"));

  assert.deepEqual(d.knownPeers.value, []);
  assert.equal(d.account.value.id, 7);
});

test("signing out clears the account, the peers and the auth form", () => {
  globalThis.localStorage = storage();
  const d = deps({ account: ref(account(7, "岳蕾", "yuelei")), knownPeers: ref([{ id: 3 }]) });
  const session = useAccountSession(d);
  session.resetClientState();

  assert.equal(d.account.value, null);
  assert.deepEqual(d.knownPeers.value, []);
  assert.deepEqual(d.mentionSelections.value, {});
  assert.equal(d.page.value, "chat");
  assert.ok(d.log.includes("history.close"));
  assert.ok(d.log.includes("context.close"));
  assert.ok(d.log.includes("realtime.stop"));
  assert.ok(d.log.includes("auth.reset"));
});

test("a token the server already forgot still lands on the auth screen", async () => {
  const store = login();
  globalThis.localStorage = storage();
  globalThis.fetch = async () => Response.json({ message: "登录已过期" }, { status: 401 });
  const d = deps({ account: ref(account(7, "岳蕾", "yuelei")) });
  const session = useAccountSession(d);
  await session.signOut();

  assert.equal(d.account.value, null);
  assert.ok(d.log.includes("auth.reset"));
  assert.deepEqual(d.notes, []);
  assert.equal(store.store.has(sessionKey), false);
});

test("a server fault during sign-out keeps the session and says so", async () => {
  login();
  globalThis.localStorage = storage();
  globalThis.fetch = async () => Response.json({ message: "服务暂时不可用" }, { status: 500 });
  const d = deps({ account: ref(account(7, "岳蕾", "yuelei")) });
  const session = useAccountSession(d);
  await session.signOut();

  assert.equal(d.account.value.id, 7);
  assert.deepEqual(d.notes, ["服务暂时不可用"]);
  assert.ok(!d.log.includes("auth.reset"));
});

test("session expiry resets exactly once and points at the auth form", () => {
  globalThis.localStorage = storage();
  const d = deps({ account: ref(account(7, "岳蕾", "yuelei")) });
  const session = useAccountSession(d);
  session.restoring.value = true;
  session.handleExpiry();

  assert.equal(d.account.value, null);
  assert.equal(session.restoring.value, false);
  assert.equal(d.auth.error.value, "登录已过期，请重新登录");
  assert.equal(d.log.filter((entry) => entry === "auth.reset").length, 1);

  // A second 401 for the same session must not reset an empty shell again.
  session.handleExpiry();
  assert.equal(d.log.filter((entry) => entry === "auth.reset").length, 1);
});

test("restoring without a token skips the request entirely", async () => {
  globalThis.sessionStorage = storage();
  globalThis.localStorage = storage();
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    return Response.json({ id: 7 });
  };
  const d = deps();
  const session = useAccountSession(d);
  await session.restore();

  assert.equal(calls, 0);
  assert.equal(session.restoring.value, false);
  assert.equal(d.account.value, null);
});

test("a dead stored token is dropped and explained", async () => {
  const store = login();
  globalThis.localStorage = storage();
  globalThis.fetch = async () => Response.json({ message: "登录已过期" }, { status: 401 });
  const d = deps();
  const session = useAccountSession(d);
  await session.restore();

  assert.equal(session.restoring.value, false);
  assert.equal(d.account.value, null);
  assert.equal(d.auth.error.value, "登录已过期");
  assert.equal(store.store.has(sessionKey), false);
});

test("resetting demo data keeps the conversation selection honest", () => {
  globalThis.confirm = () => false;
  const d = deps();
  const session = useAccountSession(d);
  session.resetDemo();
  assert.deepEqual(d.notes, []);
  assert.equal(d.data.value.displayName, "旧名");

  globalThis.confirm = () => true;
  session.resetDemo();
  assert.equal(d.activeId.value, "server-group-7");
  assert.match(d.notes[0], /本地演示数据已重置/);
});
