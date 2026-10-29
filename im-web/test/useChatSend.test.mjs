import test from "node:test";
import assert from "node:assert/strict";
import { ref } from "vue";
import { sessionKey } from "../src/session.ts";
import { useChatSend } from "../src/useChatSend.ts";

function login() {
  const store = new Map([[sessionKey, "token"]]);
  globalThis.sessionStorage = {
    getItem: (key) => store.get(key) ?? null,
    setItem: (key, value) => store.set(key, value),
    removeItem: (key) => store.delete(key),
  };
  globalThis.window = { confirm: () => true };
}

function deps(overrides = {}) {
  const state = { direct: [], group: [], directRecalls: [], groupRecalls: [], notes: [], errors: [] };
  return {
    state,
    accountId: () => 7,
    activeId: () => "server-user-3",
    canWrite: () => true,
    contextActive: () => false,
    composer: ref(""),
    mentionSelections: ref({}),
    voiceDraft: ref(""),
    error: ref(""),
    mergeDirect: (messages) => state.direct.push(...messages),
    mergeGroup: (messages) => state.group.push(...messages),
    applyDirectRecall: (recall) => state.directRecalls.push(recall),
    applyGroupRecall: (recall) => state.groupRecalls.push(recall),
    refreshSummaries: () => {},
    stopVoice: () => {},
    scrollBottom: () => {},
    refreshHidden: () => {},
    notify: (message) => state.notes.push(message),
    ...overrides,
  };
}

const saved = (id, body) => ({
  id, senderId: 7, recipientId: 3, clientMsgId: `c-${id}`, body,
  createdAt: "2026-10-29T10:00:00Z", recalledAt: null,
});

test("a retried send reuses the first client id so the server can dedupe", async () => {
  login();
  const bodies = [];
  let attempt = 0;
  globalThis.fetch = async (url, init) => {
    bodies.push(JSON.parse(init.body));
    attempt++;
    if (attempt === 1) return new Response(JSON.stringify({ message: "网络抖动" }), { status: 500 });
    return Response.json(saved(21, "在吗"));
  };
  const d = deps({ composer: ref("在吗") });
  const outbox = useChatSend(d);

  await outbox.send();
  assert.equal(outbox.sending.value, false);
  assert.equal(d.error.value, "网络抖动");
  assert.equal(d.composer.value, "在吗", "a failed send keeps the draft");
  assert.deepEqual(d.state.direct, []);

  await outbox.send();
  assert.equal(bodies.length, 2);
  assert.equal(bodies[0].clientMsgId, bodies[1].clientMsgId);
  assert.equal(d.state.direct.length, 1);
  assert.equal(d.composer.value, "");
  assert.equal(d.error.value, "");
});

test("a different text starts a new send identity", async () => {
  login();
  const bodies = [];
  globalThis.fetch = async (url, init) => {
    bodies.push(JSON.parse(init.body));
    return new Response(JSON.stringify({ message: "boom" }), { status: 500 });
  };
  const composer = ref("第一句");
  const outbox = useChatSend(deps({ composer }));

  await outbox.send();
  composer.value = "第二句";
  await outbox.send();

  assert.notEqual(bodies[0].clientMsgId, bodies[1].clientMsgId);
});

test("only mentions still present in the text reach the server", async () => {
  login();
  let body;
  globalThis.fetch = async (url, init) => {
    body = JSON.parse(init.body);
    return Response.json({
      id: 8, groupId: 5, senderId: 7, clientMsgId: body.clientMsgId, body: body.body,
      kind: "TEXT", createdAt: "2026-10-29T10:00:00Z", recalledAt: null,
    });
  };
  const mentionSelections = ref({ 4: "@小明#4", 5: "@小红#5" });
  const d = deps({
    activeId: () => "server-group-5",
    composer: ref("@小明#4 看一下"),
    mentionSelections,
  });
  const outbox = useChatSend(d);

  await outbox.send();

  assert.deepEqual(body.mentionedUserIds, [4]);
  assert.equal(body.kind, "TEXT");
  assert.equal(d.composer.value, "");
  assert.deepEqual(mentionSelections.value, {});
});

test("a caption is sent as a non-text kind and clears the spoken draft", async () => {
  login();
  let body;
  globalThis.fetch = async (url, init) => {
    body = JSON.parse(init.body);
    return Response.json({
      id: 9, groupId: 5, senderId: 7, clientMsgId: body.clientMsgId, body: body.body,
      kind: "CAPTION", createdAt: "2026-10-29T10:00:00Z", recalledAt: null,
    });
  };
  const voiceDraft = ref("这是语音转写");
  const d = deps({ activeId: () => "server-group-5", voiceDraft });
  const outbox = useChatSend(d);

  await outbox.sendCaption(false);

  assert.equal(body.kind, "CAPTION");
  assert.deepEqual(body.mentionedUserIds, []);
  assert.equal(voiceDraft.value, "");
});

test("a message outside the recall window is refused before any request", async () => {
  login();
  let calls = 0;
  globalThis.fetch = async () => { calls++; return Response.json({}); };
  const d = deps();
  const outbox = useChatSend(d);
  const old = {
    id: "server-message-3", senderId: "me", recalled: false, text: "旧消息",
    createdAt: new Date(Date.now() - 5 * 60_000).toISOString(),
  };

  assert.equal(outbox.canRecall(old), false);
  await outbox.recall(old);
  assert.equal(calls, 0);
  assert.deepEqual(d.state.directRecalls, []);
});

test("recalling a fresh message redacts it immediately", async () => {
  login();
  const requests = [];
  globalThis.fetch = async (url, init) => {
    requests.push(`${init?.method ?? "GET"} ${String(url)}`);
    return Response.json({});
  };
  const state = { refreshed: 0 };
  const d = deps({ refreshSummaries: () => state.refreshed++ });
  const outbox = useChatSend(d);
  const fresh = {
    id: "server-message-4", senderId: "me", recalled: false, text: "刚发的",
    createdAt: new Date().toISOString(),
  };

  await outbox.recall(fresh);

  assert.equal(requests.length, 1);
  assert.match(requests[0], /^DELETE .*\/chats\/direct\/3\/messages\/4$/);
  assert.equal(d.state.directRecalls.length, 1);
  assert.equal(d.state.directRecalls[0].messageId, 4);
  assert.equal(outbox.recallingId.value, null);
  assert.deepEqual(d.state.notes, ["消息已撤回"]);
});
