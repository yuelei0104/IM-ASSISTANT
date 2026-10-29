import test from "node:test";
import assert from "node:assert/strict";
import { onSessionExpired, sessionKey } from "../src/session.ts";
import { useContacts } from "../src/useContacts.ts";

function login() {
  const store = new Map([[sessionKey, "token"]]);
  globalThis.sessionStorage = {
    getItem: (key) => store.get(key) ?? null,
    setItem: (key, value) => store.set(key, value),
    removeItem: (key) => store.delete(key),
  };
  return store;
}

function deps(overrides = {}) {
  const peers = [];
  const notes = [];
  const routes = [];
  return {
    peers,
    notes,
    routes,
    accountId: () => 7,
    rememberPeers: (profiles) => peers.push(...profiles),
    showRequests: () => routes.push("requests"),
    notify: (message) => notes.push(message),
    ...overrides,
  };
}

const friend = (id, nickname) => ({ id, nickname, handle: `user${id}` });

test("refreshing loads friends and requests and feeds the peer cache", async () => {
  login();
  globalThis.fetch = async (url) => {
    if (url.startsWith("/api/contacts/requests"))
      return Response.json({ items: [{ id: 5, state: "PENDING" }], nextCursor: 5 });
    return Response.json({ items: [friend(3, "张三")], nextCursor: 3 });
  };
  const d = deps();
  const contacts = useContacts(d);
  await contacts.refresh();

  assert.deepEqual(contacts.items.value.map((item) => item.id), [3]);
  assert.equal(contacts.requests.value.length, 1);
  assert.equal(contacts.friendsCursor.value, 3);
  assert.equal(contacts.requestsCursor.value, 5);
  assert.deepEqual(d.peers.map((profile) => profile.id), [3]);
  assert.equal(contacts.busy.value, false);
});

test("an invalid invite is rejected before any request leaves the client", async () => {
  login();
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    return Response.json({});
  };
  const contacts = useContacts(deps());
  contacts.searchType.value = "phone";
  contacts.query.value = "12345";
  await contacts.invite();

  assert.equal(calls, 0);
  assert.match(contacts.inviteError.value, /手机号/);
  assert.equal(contacts.inviteBusy.value, false);
});

test("a valid invite posts the normalized handle then opens the requests page", async () => {
  login();
  const posted = [];
  globalThis.fetch = async (url, options) => {
    if (options.method === "POST") {
      posted.push({ url, body: JSON.parse(options.body) });
      return new Response(null, { status: 202 });
    }
    if (url.startsWith("/api/contacts/requests"))
      return Response.json({ items: [], nextCursor: null });
    return Response.json({ items: [], nextCursor: null });
  };
  const d = deps();
  const contacts = useContacts(d);
  contacts.searchType.value = "handle";
  contacts.query.value = "  @Alice_01 ";
  contacts.note.value = " 同事 ";
  await contacts.invite();

  assert.deepEqual(posted, [{ url: "/api/contacts/requests", body: { handle: "alice_01", note: "同事" } }]);
  assert.deepEqual(d.routes, ["requests"]);
  assert.match(d.notes[0], /如果账号存在/);
});

test("a 401 is left to the shared session listener instead of a local error", async () => {
  login();
  let expired = 0;
  const stop = onSessionExpired(() => expired++);
  globalThis.fetch = async () => Response.json({ message: "登录已过期" }, { status: 401 });
  const contacts = useContacts(deps());
  await contacts.refresh();
  stop();

  assert.equal(expired, 1);
  assert.equal(contacts.error.value, "");
  assert.deepEqual(contacts.items.value, []);
  assert.equal(contacts.busy.value, false);
});

test("a stale friend page cannot land after the account is reset", async () => {
  login();
  const releases = [];
  globalThis.fetch = () => new Promise((resolve) => {
    releases.push(() => resolve(Response.json({ items: [friend(9, "上一个账号")], nextCursor: 9 })));
  });
  const d = deps();
  const contacts = useContacts(d);
  const pending = contacts.refresh();
  contacts.reset();
  for (const release of releases) release();
  await pending;

  assert.equal(releases.length, 2); // friends and requests are fetched together
  assert.deepEqual(contacts.items.value, []);
  assert.deepEqual(d.peers, []);
  assert.equal(contacts.busy.value, false);
});

test("removing a friend reads the peer id out of the conversation id", async () => {
  login();
  const deleted = [];
  globalThis.confirm = () => true;
  globalThis.fetch = async (url, options) => {
    if (options.method === "DELETE") deleted.push(url);
    return new Response(null, { status: 204 });
  };
  const d = deps();
  const contacts = useContacts(d);
  await contacts.remove({ id: "server-11", name: "李四" });

  assert.deepEqual(deleted, ["/api/contacts/11"]);
  assert.deepEqual(d.notes, ["已移除好友"]);
});
