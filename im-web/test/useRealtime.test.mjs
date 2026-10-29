import test from "node:test";
import assert from "node:assert/strict";
import { useRealtime } from "../src/useRealtime.ts";

function dom(hidden = false) {
  const listeners = new Map();
  globalThis.document = {
    hidden,
    addEventListener: (type, handler) => listeners.set(type, handler),
    removeEventListener: (type) => listeners.delete(type),
  };
  return { listeners, hide: () => { globalThis.document.hidden = true; }, show: () => { globalThis.document.hidden = false; } };
}

function deps(overrides = {}) {
  const calls = [];
  return {
    calls,
    accountId: () => 7,
    activeId: () => "server-user-3",
    page: () => "chat",
    refreshUnread: () => calls.push("unread"),
    refreshHidden: () => calls.push("hidden"),
    refreshGroups: () => { calls.push("groups"); return Promise.resolve(); },
    refreshNotices: () => calls.push("notices"),
    reconcileMembership: async (groupId) => calls.push(`membership:${groupId}`),
    requestDirect: () => calls.push("direct"),
    requestGroup: () => calls.push("group"),
    requestReceipt: (peerId) => calls.push(`receipt:${peerId}`),
    pauseVoice: () => calls.push("pause"),
    ...overrides,
  };
}

const tick = () => new Promise((resolve) => setTimeout(resolve, 0));

test("a direct hint refreshes counters, the list and the open thread", () => {
  dom();
  const d = deps();
  const realtime = useRealtime(d);
  realtime.refresh("direct");
  assert.deepEqual(d.calls, ["unread", "hidden", "direct"]);
  realtime.dispose();
});

test("a hint without a kind recovers everything", () => {
  dom();
  const d = deps();
  const realtime = useRealtime(d);
  realtime.refresh();
  assert.deepEqual(d.calls, ["unread", "hidden", "groups", "notices", "direct"]);
  realtime.dispose();
});

test("a membership change reloads the room list before re-checking the room", async () => {
  dom();
  const d = deps();
  const realtime = useRealtime(d);
  realtime.refresh("group", 5);
  await tick();
  assert.deepEqual(d.calls, ["groups", "membership:5"]);
  realtime.dispose();
});

test("a receipt hint only re-reads that peer's cursor", () => {
  dom();
  const d = deps();
  const realtime = useRealtime(d);
  realtime.refresh("receipt", 3);
  realtime.refresh("receipt", 4);
  assert.deepEqual(d.calls, ["receipt:3"]);
  realtime.dispose();
});

test("hiding the tab stops recording, returning refreshes the open thread", () => {
  const tab = dom();
  const d = deps();
  const realtime = useRealtime(d);
  const onChange = tab.listeners.get("visibilitychange");

  tab.hide();
  onChange();
  assert.deepEqual(d.calls, ["pause"]);

  tab.show();
  onChange();
  assert.deepEqual(d.calls, ["pause", "unread", "hidden", "groups", "notices", "direct"]);
  realtime.dispose();
  assert.equal(tab.listeners.has("visibilitychange"), false);
});

test("nothing is refreshed while signed out", () => {
  dom();
  const d = deps({ accountId: () => null });
  const realtime = useRealtime(d);
  realtime.refresh();
  realtime.refresh("direct");
  assert.deepEqual(d.calls, []);
  realtime.dispose();
});
