import test from "node:test";
import assert from "node:assert/strict";
import { sessionKey } from "../src/authApi.ts";
import { listMentionNotices, markMentionRead, markAllMentionsRead } from "../src/noticeApi.ts";
import { sendGroupMessage, getGroupMessage } from "../src/groupApi.ts";

test("mentions and notifications use authenticated requests with a message cursor", async () => {
  globalThis.sessionStorage = { getItem: (key) => key === sessionKey ? "member-token" : null };
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    assert.equal(options.headers.Authorization, "Bearer member-token");
    if (url.includes("/notices?")) return Response.json({ items: [], nextBeforeId: null, unreadCount: 3 });
    if (options.method === "GET") return Response.json({ id: 50, groupId: 4 });
    return url.endsWith("/read") || url.endsWith("/read-all")
      ? new Response(null, { status: 204 }) : Response.json({ id: 50 });
  };
  await sendGroupMessage(4, "e9873e73-1326-4329-a4c0-936912a48e37", "@伙伴#10 请看", [10]);
  assert.deepEqual(JSON.parse(calls[0].options.body).mentionedUserIds, [10]);
  await listMentionNotices(22);
  assert.equal(calls[1].url, "/api/chats/notices?limit=50&beforeId=22");
  await markMentionRead(7);
  assert.equal(calls[2].url, "/api/chats/notices/7/read");
  await markAllMentionsRead();
  assert.equal(calls[3].url, "/api/chats/notices/read-all");
  await getGroupMessage(4, 50);
  assert.equal(calls[4].url, "/api/chats/groups/4/messages/50");
});
