import test from "node:test";
import assert from "node:assert/strict";
import { sessionKey } from "../src/authApi.ts";
import { loadDirectMessages, syncDirectMessages } from "../src/chatApi.ts";
import { loadGroupMessages, syncGroupMessages } from "../src/groupApi.ts";

test("message context uses separate bounded history pages in both directions", async () => {
  globalThis.sessionStorage = { getItem: (key) => key === sessionKey ? "session" : null };
  const urls = [];
  globalThis.fetch = async (url, options) => {
    assert.equal(options.headers.Authorization, "Bearer session");
    urls.push(url);
    return Response.json({ messages: [], nextBeforeId: null, nextAfterId: null });
  };
  await loadDirectMessages(3, 80, 20);
  await syncDirectMessages(3, 80, 20);
  await loadGroupMessages(5, 80, 20);
  await syncGroupMessages(5, 80, 20);
  assert.deepEqual(urls, [
    "/api/chats/direct/3/messages?beforeId=80&limit=20",
    "/api/chats/direct/3/messages/sync?afterId=80&limit=20",
    "/api/chats/groups/5/messages?limit=20&beforeId=80",
    "/api/chats/groups/5/messages/sync?afterId=80&limit=20",
  ]);
});
