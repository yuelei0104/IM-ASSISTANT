import test from "node:test";
import assert from "node:assert/strict";
import { connectChatSocket, listUnreadDirect, loadDirectMessages, syncDirectMessages, markDirectRead, sendDirectMessage } from "../src/chatApi.ts";
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
  await syncDirectMessages(2, 90);
  assert.equal(calls[1].url, "/api/chats/direct/2/messages/sync?afterId=90&limit=50");
  await sendDirectMessage(2, "e9873e73-1326-4329-a4c0-936912a48e37", "你好");
  assert.deepEqual(JSON.parse(calls[2].options.body), {
    clientMsgId: "e9873e73-1326-4329-a4c0-936912a48e37", body: "你好",
  });
});

test("socket authenticates without URL token and refreshes after a notification", () => {
  globalThis.sessionStorage = { getItem: (key) => key === sessionKey ? "alice-token" : null };
  globalThis.window = { location: { href: "http://localhost:5173/" } };
  let opened;
  globalThis.WebSocket = class {
    static OPEN = 1;
    readyState = 1;
    sent = [];
    constructor(url) { this.url = String(url); opened = this; }
    send(value) { this.sent.push(value); }
    close() { this.readyState = 3; this.onclose?.(); }
  };
  let updates = 0;
  const connection = connectChatSocket(() => updates++);
  assert.equal(opened.url, "ws://localhost:5173/api/chats/ws");
  opened.onopen();
  assert.deepEqual(opened.sent, ["AUTH alice-token"]);
  opened.onmessage({ data: "READY" });
  opened.onmessage({ data: "MSG 42 2 1" });
  opened.onmessage({ data: "GROUP 7 42" });
  opened.onmessage({ data: "NOTICE" });
  assert.equal(connection.connected(), true);
  assert.equal(updates, 4);
  connection.close();
  assert.equal(connection.connected(), false);
});

test("an empty proxy error reports its HTTP status", async () => {
  globalThis.sessionStorage = { getItem: (key) => key === sessionKey ? "alice-token" : null };
  globalThis.fetch = async () => new Response("", { status: 500 });
  await assert.rejects(loadDirectMessages(2), /HTTP 500.*8082/);
});

test("unread summary and read receipt use authenticated endpoints", async () => {
  globalThis.sessionStorage = { getItem: (key) => key === sessionKey ? "alice-token" : null };
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    assert.equal(options.headers.Authorization, "Bearer alice-token");
    return options.method === "GET"
      ? Response.json([{ peerId: 2, unreadCount: 3 }])
      : new Response(null, { status: 204 });
  };
  assert.deepEqual(await listUnreadDirect(), [{ peerId: 2, unreadCount: 3 }]);
  assert.equal(calls[0].url, "/api/chats/direct/unread");
  await markDirectRead(2, 41);
  assert.equal(calls[1].url, "/api/chats/direct/2/read");
  assert.deepEqual(JSON.parse(calls[1].options.body), { lastReadMessageId: 41 });
});
