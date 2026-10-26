import test from "node:test";
import assert from "node:assert/strict";
import {
  createGroupChat, inviteGroupMembers, leaveGroup, removeGroupMember, transferGroupOwner, dissolveGroup,
  listGroups, loadGroupMessages, recallGroupMessage, listGroupRecalls,
  markGroupRead, sendGroupMessage, syncGroupMessages,
  listGroupSummaries, generateGroupSummary, listGroupSummarySources,
} from "../src/groupApi.ts";
import { sessionKey } from "../src/authApi.ts";

test("group requests use bearer auth, membership IDs, stable client ID and cursors", async () => {
  globalThis.sessionStorage = { getItem: (key) => key === sessionKey ? "group-token" : null };
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    assert.equal(options.headers.Authorization, "Bearer group-token");
    return options.method === "DELETE" || url.endsWith("/read")
      ? new Response(null, { status: 204 })
      : options.method === "GET"
      ? Response.json(url.includes("/summaries") ? [] : url.includes("messages") ? { messages: [], nextBeforeId: null } : { items: [], nextBeforeId: null })
      : Response.json({ id: 4 });
  };
  await listGroups(12);
  await createGroupChat("讨论", [2, 3]);
  await inviteGroupMembers(4, [5]);
  await leaveGroup(4);
  await removeGroupMember(4, 5);
  await transferGroupOwner(4, 5);
  await dissolveGroup(4);
  await loadGroupMessages(4, 31);
  await syncGroupMessages(4, 31);
  await sendGroupMessage(4, "e9873e73-1326-4329-a4c0-936912a48e37", "你好");
  await sendGroupMessage(4, "e9873e73-1326-4329-a4c0-936912a48e38", "字幕片段", [], "CAPTION");
  await markGroupRead(4, 34);
  assert.deepEqual(await listGroupSummaries(4), []);
  await generateGroupSummary(4);
  assert.deepEqual(await listGroupSummarySources(4, 7), []);
  assert.equal(calls[0].url, "/api/chats/groups?limit=50&beforeId=12");
  assert.deepEqual(JSON.parse(calls[1].options.body), { name: "讨论", friendIds: [2, 3] });
  assert.deepEqual(JSON.parse(calls[2].options.body), { friendIds: [5] });
  assert.equal(calls[3].url, "/api/chats/groups/4/members/me");
  assert.equal(calls[3].options.method, "DELETE");
  assert.equal(calls[4].url, "/api/chats/groups/4/members/5");
  assert.equal(calls[4].options.method, "DELETE");
  assert.equal(calls[5].url, "/api/chats/groups/4/owner");
  assert.deepEqual(JSON.parse(calls[5].options.body), { memberId: 5 });
  assert.equal(calls[6].url, "/api/chats/groups/4");
  assert.equal(calls[6].options.method, "DELETE");
  assert.equal(calls[7].url, "/api/chats/groups/4/messages?limit=50&beforeId=31");
  assert.equal(calls[8].url, "/api/chats/groups/4/messages/sync?afterId=31&limit=50");
  assert.deepEqual(JSON.parse(calls[9].options.body), {
    clientMsgId: "e9873e73-1326-4329-a4c0-936912a48e37", body: "你好", mentionedUserIds: [], kind: "TEXT",
  });
  assert.deepEqual(JSON.parse(calls[10].options.body), {
    clientMsgId: "e9873e73-1326-4329-a4c0-936912a48e38", body: "字幕片段", mentionedUserIds: [], kind: "CAPTION",
  });
  assert.deepEqual(JSON.parse(calls[11].options.body), { lastReadMessageId: 34 });
  assert.equal(calls[12].url, "/api/chats/groups/4/summaries");
  assert.equal(calls[13].url, "/api/chats/groups/4/summaries");
  assert.equal(calls[13].options.method, "POST");
  assert.equal(calls[14].url, "/api/chats/groups/4/summaries/7/sources");
  assert.equal(calls[14].options.method, "GET");
});

test("group requests fail without a session before touching the network", async () => {
  globalThis.sessionStorage = { getItem: () => null };
  globalThis.fetch = () => { throw new Error("unexpected network request"); };
  await assert.rejects(listGroups(), /请先登录/);
  await assert.rejects(generateGroupSummary(4), /请先登录/);
  await assert.rejects(listGroupSummarySources(4, 7), /请先登录/);
  await assert.rejects(leaveGroup(4), /请先登录/);
  await assert.rejects(transferGroupOwner(4, 5), /请先登录/);
  await assert.rejects(dissolveGroup(4), /请先登录/);
  await assert.rejects(recallGroupMessage(4, 42), /请先登录/);
});

test("group recall and its change feed are group-scoped", async () => {
  globalThis.sessionStorage = { getItem: (key) => key === sessionKey ? "group-token" : null };
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    return options.method === "DELETE" ? new Response(null, { status: 204 })
      : Response.json({ items: [], nextAfterId: null });
  };
  await recallGroupMessage(4, 42);
  await listGroupRecalls(4, 3);
  assert.equal(calls[0].url, "/api/chats/groups/4/messages/42");
  assert.equal(calls[0].options.method, "DELETE");
  assert.equal(calls[1].url, "/api/chats/groups/4/messages/recalls?afterId=3&limit=50");
  assert.ok(calls.every((call) => call.options.headers.Authorization === "Bearer group-token"));
});
