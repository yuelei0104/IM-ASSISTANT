import test from "node:test";
import assert from "node:assert/strict";
import { ref } from "vue";
import { useGroupAdmin } from "../src/useGroupAdmin.ts";
import { sessionKey } from "../src/session.ts";

function login() {
  const store = new Map([[sessionKey, "token"]]);
  globalThis.sessionStorage = {
    getItem: (key) => store.get(key) ?? null,
    setItem: (key, value) => store.set(key, value),
    removeItem: (key) => store.delete(key),
  };
  return store;
}

const room = (overrides = {}) => ({
  id: 7,
  name: "设计组",
  ownerId: 7,
  memberIds: [7, 3],
  unreadCount: 0,
  ...overrides,
});
const view = (overrides = {}) => ({
  id: "server-group-7",
  name: "设计组",
  kind: "group",
  memberIds: ["me", "user-3"],
  ownerId: "me",
  unread: 0,
  color: "teal",
  initials: "设",
  ...overrides,
});

function deps(overrides = {}) {
  const state = { open: [], forgotten: [], notes: [], closed: 0 };
  return {
    state,
    activeId: () => "server-group-7",
    active: () => view(),
    serverGroups: ref([room()]),
    inviteIds: ref([]),
    groupName: ref(""),
    busy: ref(false),
    error: ref(""),
    notify: (message) => state.notes.push(message),
    openChat: (id) => state.open.push(id),
    closeModal: () => state.closed++,
    forgetGroup: (id) => state.forgotten.push(id),
    nameOf: (id) => (id === "user-3" ? "张三" : "李四"),
    ...overrides,
  };
}

test("creating a room opens it and prepends it to the list", async () => {
  login();
  const posted = [];
  globalThis.fetch = async (url, options) => {
    posted.push({ url, body: JSON.parse(options.body) });
    return Response.json(room({ id: 21, name: "新房间", memberIds: [7, 3, 4] }));
  };
  const d = deps();
  d.groupName.value = "  新房间  ";
  d.inviteIds.value = ["user-3", "user-4"];
  const admin = useGroupAdmin(d);
  await admin.create();

  assert.deepEqual(posted, [{ url: "/api/chats/groups", body: { name: "新房间", friendIds: [3, 4] } }]);
  assert.deepEqual(d.serverGroups.value.map((g) => g.id), [21, 7]);
  assert.deepEqual(d.state.open, ["server-group-21"]);
  assert.equal(d.state.closed, 1);
  assert.equal(d.busy.value, false);
});

test("a nameless room is rejected before any request leaves the client", async () => {
  login();
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    return Response.json(room());
  };
  const d = deps();
  d.groupName.value = "   ";
  const admin = useGroupAdmin(d);
  await admin.create();

  assert.equal(calls, 0);
  assert.equal(d.state.closed, 0);
});

test("an invite beyond the 50-member ceiling never leaves the client", async () => {
  login();
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    return Response.json(room());
  };
  const crowded = view({ memberIds: ["me", ...Array.from({ length: 47 }, (_, i) => `user-${i + 10}`)] });
  const d = deps({ active: () => crowded });
  d.inviteIds.value = ["user-3", "user-4", "user-5"];
  const admin = useGroupAdmin(d);
  await admin.addMembers();

  assert.equal(calls, 0);
  assert.deepEqual(d.state.notes, ["最多 50 人，每次邀请不超过 20 人"]);
  assert.equal(d.busy.value, false);
});

test("inviting members adopts the server's member list", async () => {
  login();
  const posted = [];
  globalThis.fetch = async (url, options) => {
    posted.push({ url, body: JSON.parse(options.body) });
    return Response.json(room({ memberIds: [7, 3, 12] }));
  };
  const d = deps();
  d.inviteIds.value = ["user-12"];
  const admin = useGroupAdmin(d);
  await admin.addMembers();

  assert.deepEqual(posted, [
    { url: "/api/chats/groups/7/members", body: { friendIds: [12] } },
  ]);
  assert.deepEqual(d.serverGroups.value[0].memberIds, [7, 3, 12]);
  assert.equal(d.state.closed, 1);
  assert.match(d.state.notes[0], /邀请已发送/);
});

test("leaving a room forgets it locally", async () => {
  login();
  globalThis.confirm = () => true;
  const deleted = [];
  globalThis.fetch = async (url, options) => {
    deleted.push(`${options.method} ${url}`);
    return new Response(null, { status: 204 });
  };
  const d = deps({ active: () => view({ ownerId: "user-3" }) });
  const admin = useGroupAdmin(d);
  await admin.leave();

  assert.deepEqual(deleted, ["DELETE /api/chats/groups/7/members/me"]);
  assert.deepEqual(d.state.forgotten, [7]);
  assert.deepEqual(d.state.notes, ["已退出讨论室"]);
});

test("the owner cannot leave without transferring first", async () => {
  login();
  globalThis.confirm = () => true;
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    return new Response(null, { status: 204 });
  };
  const admin = useGroupAdmin(deps());
  await admin.leave();

  assert.equal(calls, 0);
  assert.deepEqual(deps().state.forgotten, []);
});

test("removing a member sends the bare id and drops the row", async () => {
  login();
  globalThis.confirm = () => true;
  const deleted = [];
  globalThis.fetch = async (url, options) => {
    deleted.push(`${options.method} ${url}`);
    return new Response(null, { status: 204 });
  };
  const d = deps();
  const admin = useGroupAdmin(d);
  await admin.removeMember("user-3");

  assert.deepEqual(deleted, ["DELETE /api/chats/groups/7/members/3"]);
  assert.deepEqual(d.serverGroups.value[0].memberIds, [7]);
  assert.deepEqual(d.state.notes, ["成员已移出讨论室"]);
});

test("an unparseable member id is refused without a request", async () => {
  login();
  globalThis.confirm = () => true;
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    return new Response(null, { status: 204 });
  };
  const admin = useGroupAdmin(deps());
  await admin.removeMember("user-abc");

  assert.equal(calls, 0);
});

test("transferring ownership adopts the server's room", async () => {
  login();
  globalThis.confirm = () => true;
  const posted = [];
  globalThis.fetch = async (url, options) => {
    posted.push({ url, body: JSON.parse(options.body) });
    return Response.json(room({ ownerId: 3 }));
  };
  const d = deps();
  const admin = useGroupAdmin(d);
  await admin.transferOwner("user-3");

  assert.deepEqual(posted, [{ url: "/api/chats/groups/7/owner", body: { memberId: 3 } }]);
  assert.equal(d.serverGroups.value[0].ownerId, 3);
  assert.match(d.state.notes[0], /群主已转让/);
});

test("a failed dissolve keeps the room and surfaces the server message", async () => {
  login();
  globalThis.confirm = () => true;
  globalThis.fetch = async () =>
    Response.json({ message: "群主不能解散正在使用的房间" }, { status: 500 });
  const d = deps();
  const admin = useGroupAdmin(d);
  await admin.dissolve();

  assert.deepEqual(d.state.forgotten, []);
  assert.deepEqual(d.state.notes, ["群主不能解散正在使用的房间"]);
  assert.equal(d.busy.value, false);
});
