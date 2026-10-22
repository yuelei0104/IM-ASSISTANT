import test from "node:test";
import assert from "node:assert/strict";
import {
  deleteFriend,
  findUser,
  findUserByPhone,
  inviteFriend,
  listFriends,
  listRequests,
  resolveFriendRequest,
} from "../src/contactApi.ts";
import { sessionKey } from "../src/authApi.ts";

test("friend search and request lifecycle require a bearer token", async () => {
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
    if (
      url.endsWith("/users/bob_22") ||
      url.endsWith("/users/phone/13800138000")
    )
      return Response.json({ id: 22, nickname: "Bob", handle: "bob_22" });
    if (url.endsWith("/requests") && options.method === "GET")
      return Response.json([{ id: 3, fromId: 1, toId: 22, state: "PENDING" }]);
    if (url.endsWith("/requests") && options.method === "POST")
      return new Response(null, { status: 201 });
    if (url.endsWith("/contacts") && options.method === "GET")
      return Response.json([{ id: 22, nickname: "Bob", handle: "bob_22" }]);
    return new Response(null, { status: 204 });
  };

  assert.equal((await findUser("bob_22")).id, 22);
  await inviteFriend({ handle: "bob_22" }, "一起讨论项目");
  assert.deepEqual(JSON.parse(calls[1].options.body), {
    handle: "bob_22",
    note: "一起讨论项目",
  });
  assert.equal((await listRequests())[0].state, "PENDING");
  await resolveFriendRequest(3, "accept");
  assert.equal(calls[3].url, "/api/contacts/requests/3/accept");
  assert.equal((await listFriends())[0].handle, "bob_22");
  await deleteFriend(22);
  assert.equal(calls[5].options.method, "DELETE");
  assert.equal((await findUserByPhone("13800138000")).handle, "bob_22");
  await inviteFriend({ phone: "13800138000" }, "手机号添加");
  assert.deepEqual(JSON.parse(calls[7].options.body), {
    phone: "13800138000",
    note: "手机号添加",
  });
});

test("contact requests fail before sending when the session is missing", async () => {
  globalThis.sessionStorage = { getItem: () => null };
  globalThis.fetch = () => {
    throw new Error("should not be called");
  };
  await assert.rejects(listFriends(), /请先登录/);
});
