import test from "node:test";
import assert from "node:assert/strict";
import { loadDirectMessages, sendDirectMessage } from "../src/chatApi.ts";
import { sessionKey } from "../src/authApi.ts";

test("direct messages use the user's token, cursor and stable client id", async () => {
  globalThis.sessionStorage = { getItem: (key) => key === sessionKey ? "alice-token" : null };
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    assert.equal(options.headers.Authorization, "Bearer alice-token");
    return Response.json(options.method === "GET"
      ? { messages: [], nextBeforeId: null }
      : { id: 3, senderId: 1, recipientId: 2, body: "你好" });
  };
  await loadDirectMessages(2, 90);
  assert.equal(calls[0].url, "/api/chats/direct/2/messages?beforeId=90&limit=50");
  await sendDirectMessage(2, "e9873e73-1326-4329-a4c0-936912a48e37", "你好");
  assert.deepEqual(JSON.parse(calls[1].options.body), {
    clientMsgId: "e9873e73-1326-4329-a4c0-936912a48e37", body: "你好",
  });
});
