import test from "node:test";
import assert from "node:assert/strict";
import {
  createGroupChat, inviteGroupMembers, listGroups, loadGroupMessages,
  markGroupRead, sendGroupMessage,
} from "../src/groupApi.ts";
import { sessionKey } from "../src/authApi.ts";

test("group requests use bearer auth, membership IDs, stable client ID and cursors", async () => {
  globalThis.sessionStorage = { getItem: (key) => key === sessionKey ? "group-token" : null };
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    assert.equal(options.headers.Authorization, "Bearer group-token");
    return options.method === "GET"
      ? Response.json(url.includes("messages") ? { messages: [], nextBeforeId: null } : { items: [], nextBeforeId: null })
      : url.endsWith("/read") ? new Response(null, { status: 204 }) : Response.json({ id: 4 });
  };
  await listGroups(12);
  await createGroupChat("讨论", [2, 3]);
  await inviteGroupMembers(4, [5]);
  await loadGroupMessages(4, 31);
  await sendGroupMessage(4, "e9873e73-1326-4329-a4c0-936912a48e37", "你好");
  await markGroupRead(4, 34);
  assert.equal(calls[0].url, "/api/chats/groups?limit=50&beforeId=12");
  assert.deepEqual(JSON.parse(calls[1].options.body), { name: "讨论", friendIds: [2, 3] });
  assert.deepEqual(JSON.parse(calls[2].options.body), { friendIds: [5] });
  assert.equal(calls[3].url, "/api/chats/groups/4/messages?limit=50&beforeId=31");
  assert.deepEqual(JSON.parse(calls[4].options.body), {
    clientMsgId: "e9873e73-1326-4329-a4c0-936912a48e37", body: "你好", mentionedUserIds: [],
  });
  assert.deepEqual(JSON.parse(calls[5].options.body), { lastReadMessageId: 34 });
});

test("group requests fail without a session before touching the network", async () => {
  globalThis.sessionStorage = { getItem: () => null };
  globalThis.fetch = () => { throw new Error("unexpected network request"); };
  await assert.rejects(listGroups(), /请先登录/);
});
