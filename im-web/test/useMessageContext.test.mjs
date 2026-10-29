import test from "node:test";
import assert from "node:assert/strict";
import { sessionKey } from "../src/session.ts";
import { useMessageContext } from "../src/useMessageContext.ts";

function login() {
  const store = new Map([[sessionKey, "token"]]);
  globalThis.sessionStorage = {
    getItem: (key) => store.get(key) ?? null,
    setItem: (key, value) => store.set(key, value),
    removeItem: (key) => store.delete(key),
  };
}

function deps(overrides = {}) {
  const state = { focus: [], notes: [], latest: 0, opened: 0 };
  return {
    state,
    accountId: () => 7,
    scope: () => ({ peerId: 3, groupId: null, conversationId: "server-server-3" }),
    focusOn: (message) => state.focus.push(message ? message.id : null),
    showLatest: () => state.latest++,
    beforeOpen: () => state.opened++,
    recalledAt: () => undefined,
    scrollEl: () => null,
    notify: (message) => state.notes.push(message),
    ...overrides,
  };
}

const msg = (id, body = `消息 ${id}`) => ({
  id, senderId: 7, recipientId: 3, clientMsgId: `c-${id}`, body,
  createdAt: "2026-10-29T10:00:00Z", recalledAt: null,
});

test("opening a hit loads both directions and keeps the transcript ordered", async () => {
  login();
  globalThis.fetch = async (url) => url.includes("/sync")
    ? Response.json({ messages: [msg(4)], nextAfterId: 4 })
    : Response.json({ messages: [msg(1), msg(2)], nextBeforeId: 2 });
  const d = deps();
  const context = useMessageContext(d);
  await context.open(msg(3));

  assert.equal(context.active.value, true);
  assert.deepEqual(context.messages.value.map((m) => m.id), [1, 2, 3, 4]);
  assert.equal(context.beforeId.value, 2);
  assert.equal(context.afterId.value, 4);
  assert.equal(context.busy.value, false);
  assert.deepEqual(d.state.focus, [3]);
  assert.equal(d.state.opened, 1);
});

test("loading older keeps the cursor and stops when the page is exhausted", async () => {
  login();
  globalThis.fetch = async (url) => {
    if (url.includes("/sync")) return Response.json({ messages: [], nextAfterId: null });
    if (url.includes("beforeId=2"))
      return Response.json({ messages: [msg(0)], nextBeforeId: null });
    return Response.json({ messages: [msg(2)], nextBeforeId: 2 });
  };
  const context = useMessageContext(deps());
  await context.open(msg(3));
  await context.load("older");

  assert.deepEqual(context.messages.value.map((m) => m.id), [0, 2, 3]);
  assert.equal(context.beforeId.value, null);
});

test("a 403 on either direction closes the excerpt instead of showing an error", async () => {
  login();
  let forbid = false;
  globalThis.fetch = async (url) => {
    if (forbid) return Response.json({ message: "无权访问此会话" }, { status: 403 });
    return url.includes("/sync")
      ? Response.json({ messages: [], nextAfterId: null })
      : Response.json({ messages: [], nextBeforeId: 2 });
  };
  const d = deps();
  const context = useMessageContext(d);
  await context.open(msg(3));
  forbid = true;
  await context.load("older");

  assert.equal(context.active.value, false);
  assert.deepEqual(context.messages.value, []);
  assert.deepEqual(d.state.notes, ["已无权查看此讨论室"]);
});

test("closing while the excerpt is still loading discards the response", async () => {
  login();
  const releases = [];
  globalThis.fetch = () => new Promise((resolve) => {
    releases.push(() => resolve(Response.json({ messages: [msg(9)], nextBeforeId: null, nextAfterId: null })));
  });
  const d = deps();
  const context = useMessageContext(d);
  const pending = context.open(msg(3));
  context.close();
  for (const release of releases) release();
  await pending;

  assert.equal(releases.length, 2); // older and newer pages are requested together
  assert.equal(context.active.value, false);
  assert.deepEqual(context.messages.value, []);
  assert.equal(context.busy.value, false);
  assert.equal(d.state.latest, 1);
});

test("a recalled message loses its body inside the excerpt", async () => {
  login();
  globalThis.fetch = async (url) => url.includes("/sync")
    ? Response.json({ messages: [], nextAfterId: null })
    : Response.json({ messages: [msg(1)], nextBeforeId: null });
  const context = useMessageContext(deps({
    recalledAt: (message) => message.id === 3 ? "2026-10-29T10:05:00Z" : undefined,
  }));
  await context.open(msg(3, "已撤回的内容"));

  const target = context.messages.value.find((m) => m.id === 3);
  assert.equal(target.body, "");
  assert.equal(target.recalledAt, "2026-10-29T10:05:00Z");
});
