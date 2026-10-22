import test from "node:test";
import assert from "node:assert/strict";
import {
  deleteFriend,
  inviteFriend,
  listFriends,
  listRequests,
  resolveFriendRequest,
} from "../src/contactApi.ts";
import { sessionKey } from "../src/authApi.ts";

test("friend invitations and paginated lists require a bearer token", async () => {
  const store = new Map([[sessionKey, "token-for-alice"]]);
  globalThis.sessionStorage = {
    getItem: (key) => store.get(key) ?? null,
    setItem: (key, value) => store.set(key, String(value)),
    removeItem: (key) => store.delete(key),
  };
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    assert.equal(options.headers.Authorization, "Bearer token-for-alice");
    if (url.includes("/requests") && options.method === "GET")
      return Response.json({ items: [{ id: 3, fromId: 1, toId: 22, state: "PENDING" }], nextCursor: null });
    if (url.endsWith("/requests") && options.method === "POST")
      return new Response(null, { status: 202 });
    if (url.includes("/contacts") && options.method === "GET")
      return Response.json({ items: [{ id: 22, nickname: "Bob", handle: "bob_22" }], nextCursor: 22 });
    return new Response(null, { status: 204 });
  };

  await inviteFriend({ handle: "bob_22" }, "一起讨论项目");
  assert.deepEqual(JSON.parse(calls[0].options.body), {
    handle: "bob_22",
    note: "一起讨论项目",
  });
  assert.equal((await listRequests()).items[0].state, "PENDING");
  await resolveFriendRequest(3, "accept");
  assert.equal(calls[2].url, "/api/contacts/requests/3/accept");
  assert.equal((await listFriends()).items[0].handle, "bob_22");
  await deleteFriend(22);
  assert.equal(calls[4].options.method, "DELETE");
  await inviteFriend({ phone: "13800138000" }, "手机号添加");
  assert.deepEqual(JSON.parse(calls[5].options.body), {
    phone: "13800138000",
    note: "手机号添加",
  });
  assert.equal((await listFriends(22)).nextCursor, 22);
  assert.equal(calls[6].url, "/api/contacts?afterId=22");
  await listRequests(3);
  assert.equal(calls[7].url, "/api/contacts/requests?beforeId=3");
});

test("contact requests fail before sending when the session is missing", async () => {
  globalThis.sessionStorage = { getItem: () => null };
  globalThis.fetch = () => {
    throw new Error("should not be called");
  };
  await assert.rejects(listFriends(), /请先登录/);
});
