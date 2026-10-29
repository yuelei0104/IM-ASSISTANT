import test from "node:test";
import assert from "node:assert/strict";
import { ref } from "vue";
import { useGroupSummaries } from "../src/useGroupSummaries.ts";
import { sessionKey } from "../src/session.ts";

test("a group recall invalidates a summary and discards a pending generation", async () => {
  globalThis.sessionStorage = { getItem: (key) => key === sessionKey ? "member-token" : null };
  let resolveRequest;
  globalThis.fetch = () => new Promise((resolve) => { resolveRequest = resolve; });
  const summaries = useGroupSummaries(() => 3, () => 42, ref(false));
  const pending = summaries.create();
  summaries.invalidate();
  resolveRequest(Response.json({ id: 7, content: "过期摘要" }));
  await pending;
  assert.deepEqual(summaries.items.value, []);
  assert.equal(summaries.busy.value, false);
});
