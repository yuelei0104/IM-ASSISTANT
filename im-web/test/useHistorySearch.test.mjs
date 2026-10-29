import test from "node:test";
import assert from "node:assert/strict";
import { sessionKey } from "../src/session.ts";
import { useHistorySearch } from "../src/useHistorySearch.ts";

function login() {
  const store = new Map([[sessionKey, "token"]]);
  globalThis.sessionStorage = {
    getItem: (key) => store.get(key) ?? null,
    setItem: (key, value) => store.set(key, value),
    removeItem: (key) => store.delete(key),
  };
}

const deps = (overrides = {}) => ({
  accountId: () => 7,
  scope: () => ({ peerId: 3, groupId: null, conversationId: "server-server-3" }),
  ...overrides,
});

const hit = (id, body) => ({
  id, senderId: 7, recipientId: 3, clientMsgId: `c-${id}`, body, createdAt: "2026-10-29T10:00:00Z", recalledAt: null,
});

test("a keyword shorter than two characters never reaches the network", async () => {
  login();
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    return Response.json({ messages: [], nextBeforeId: null });
  };
  const history = useHistorySearch(deps());
  history.open.value = true;
  history.query.value = "发";
  await history.search();

  assert.equal(calls, 0);
  assert.match(history.error.value, /2 到 50 个字符/);
  assert.equal(history.searching.value, false);
});

test("a search records the keyword and later pages append older hits", async () => {
  login();
  const urls = [];
  globalThis.fetch = async (url) => {
    urls.push(url);
    return url.includes("beforeId=10")
      ? Response.json({ messages: [hit(5, "更早一条")], nextBeforeId: null })
      : Response.json({ messages: [hit(12, "发版记录")], nextBeforeId: 10 });
  };
  const history = useHistorySearch(deps());
  history.open.value = true;
  history.query.value = "  发版  ";
  await history.search();

  assert.equal(history.term.value, "发版");
  assert.deepEqual(history.results.value.map((m) => m.id), [12]);
  assert.equal(history.nextBeforeId.value, 10);

  await history.search(10);
  assert.deepEqual(history.results.value.map((m) => m.id), [12, 5]);
  assert.equal(history.nextBeforeId.value, null);
  assert.match(urls[1], /beforeId=10/);
});

test("closing the panel drops a result that is still in flight", async () => {
  login();
  let release;
  globalThis.fetch = () => new Promise((resolve) => {
    release = () => resolve(Response.json({ messages: [hit(9, "迟到的结果")], nextBeforeId: null }));
  });
  const history = useHistorySearch(deps());
  history.open.value = true;
  history.query.value = "发版";
  const pending = history.search();
  history.close();
  release();
  await pending;

  assert.deepEqual(history.results.value, []);
  assert.equal(history.term.value, "");
  assert.equal(history.searching.value, false);
});

test("switching conversations abandons the pending search", async () => {
  login();
  let scope = { peerId: 3, groupId: null, conversationId: "server-server-3" };
  let release;
  globalThis.fetch = () => new Promise((resolve) => {
    release = () => resolve(Response.json({ messages: [hit(9, "上个会话")], nextBeforeId: null }));
  });
  const history = useHistorySearch(deps({ scope: () => scope }));
  history.open.value = true;
  history.query.value = "发版";
  const pending = history.search();
  scope = { peerId: 4, groupId: null, conversationId: "server-server-4" };
  release();
  await pending;

  assert.deepEqual(history.results.value, []);
});
