import test from "node:test";
import assert from "node:assert/strict";
import { useGroupAgent } from "../src/useGroupAgent.ts";
import { sessionKey } from "../src/session.ts";

test("a recall clears an Agent answer even when the recalled message was not cited", async () => {
  globalThis.sessionStorage = { getItem: (key) => key === sessionKey ? "member-token" : null };
  globalThis.fetch = async () => Response.json({ answer: "结论", sources: [{ id: 7, body: "证据" }] });
  const agent = useGroupAgent(() => 3, () => 42, () => "server-group-3");
  agent.question.value = "讨论结论是什么";
  await agent.ask();
  assert.equal(agent.answer.value?.sources[0].id, 7);
  agent.invalidateOnRecall(); // Message 8 was context but not a cited source.
  assert.equal(agent.answer.value, null);
  assert.match(agent.error.value, /重新提问/);
});

test("a recall while the model is working discards its late answer", async () => {
  globalThis.sessionStorage = { getItem: (key) => key === sessionKey ? "member-token" : null };
  let resolveRequest;
  globalThis.fetch = () => new Promise((resolve) => { resolveRequest = resolve; });
  const agent = useGroupAgent(() => 3, () => 42, () => "server-group-3");
  agent.question.value = "下一次会议的结论";
  const pending = agent.ask();
  agent.invalidateOnRecall();
  resolveRequest(Response.json({ answer: "过期结论", sources: [{ id: 7, body: "原文" }] }));
  await pending;
  assert.equal(agent.answer.value, null);
  assert.equal(agent.busy.value, false);
});
