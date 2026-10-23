import test from "node:test";
import assert from "node:assert/strict";
import { sessionKey } from "../src/authApi.ts";
import { searchDirectMessages } from "../src/chatApi.ts";
import { searchGroupMessages } from "../src/groupApi.ts";

test("historical search sends a scoped, encoded, authenticated cursor request", async () => {
  globalThis.sessionStorage = { getItem: (key) => key === sessionKey ? "private-token" : null };
  const requests = [];
  globalThis.fetch = async (url, options) => {
    requests.push({ url, options });
    return Response.json({ messages: [], nextBeforeId: null });
  };
  await searchDirectMessages(7, "会议 纪要%", 120);
  await searchGroupMessages(9, "问题_跟进", 80);
  assert.equal(requests[0].url, "/api/chats/direct/7/messages/search?q=%E4%BC%9A%E8%AE%AE%20%E7%BA%AA%E8%A6%81%25&limit=20&beforeId=120");
  assert.equal(requests[1].url, "/api/chats/groups/9/messages/search?q=%E9%97%AE%E9%A2%98_%E8%B7%9F%E8%BF%9B&limit=20&beforeId=80");
  assert.ok(requests.every(({ options }) => options.headers.Authorization === "Bearer private-token"));
});

test("search without a login does not send a request", async () => {
  globalThis.sessionStorage = { getItem: () => null };
  globalThis.fetch = () => { throw new Error("unexpected network request"); };
  await assert.rejects(searchDirectMessages(7, "会议"), /请先登录/);
  await assert.rejects(searchGroupMessages(9, "会议"), /请先登录/);
});
