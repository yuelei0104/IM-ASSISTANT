import test from "node:test";
import assert from "node:assert/strict";
import { requestJson } from "../src/apiClient.ts";
import { onSessionExpired, sessionKey } from "../src/session.ts";

test("one expired session stops authenticated requests across API modules", async () => {
  const values = new Map([[sessionKey, "expired-token"]]);
  globalThis.sessionStorage = {
    getItem: (key) => values.get(key) ?? null,
    removeItem: (key) => values.delete(key),
  };
  let networkCalls = 0;
  let expired = 0;
  const unsubscribe = onSessionExpired(() => expired++);
  globalThis.fetch = async () => {
    networkCalls++;
    return Response.json({ message: "登录已过期" }, { status: 401 });
  };
  try {
    await assert.rejects(requestJson("/api/chats/groups"), /登录已过期/);
    assert.equal(values.has(sessionKey), false);
    assert.equal(expired, 1);
    await assert.rejects(requestJson("/api/chats/notices"), /请先登录/);
    assert.equal(networkCalls, 1);
  } finally { unsubscribe(); }
});

test("a late 401 for an older login cannot log out a newer login", async () => {
  const values = new Map([[sessionKey, "old-token"]]);
  globalThis.sessionStorage = {
    getItem: (key) => values.get(key) ?? null,
    removeItem: (key) => values.delete(key),
  };
  let resolveRequest;
  globalThis.fetch = () => new Promise((resolve) => { resolveRequest = resolve; });
  const pending = requestJson("/api/chats/groups");
  values.set(sessionKey, "new-token");
  resolveRequest(Response.json({ message: "登录已过期" }, { status: 401 }));
  await assert.rejects(pending, /登录已过期/);
  assert.equal(values.get(sessionKey), "new-token");
});

test("a stalled request reports a timeout and aborts its fetch", async () => {
  globalThis.sessionStorage = { getItem: () => "valid-token" };
  let aborted = false;
  globalThis.fetch = (_url, options) => new Promise((_resolve, reject) => {
    options.signal.addEventListener("abort", () => { aborted = true; reject(new Error("aborted")); });
  });
  await assert.rejects(requestJson("/api/chats/groups", { timeoutMs: 5 }), /请求超时/);
  assert.equal(aborted, true);
});
