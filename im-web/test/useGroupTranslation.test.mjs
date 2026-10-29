import test from "node:test";
import assert from "node:assert/strict";
import { ref } from "vue";
import { sessionKey } from "../src/session.ts";
import { useGroupTranslation } from "../src/useGroupTranslation.ts";

function login() {
  const store = new Map([[sessionKey, "token"]]);
  globalThis.sessionStorage = {
    getItem: (key) => store.get(key) ?? null,
    setItem: (key, value) => store.set(key, value),
    removeItem: (key) => store.delete(key),
  };
}

function deps(overrides = {}) {
  const state = { notes: [] };
  return {
    state,
    accountId: () => 7,
    activeId: () => "server-group-5",
    target: () => "en",
    canWrite: () => true,
    contextActive: () => false,
    composer: ref(""),
    notify: (message) => state.notes.push(message),
    ...overrides,
  };
}

const message = (id, overrides = {}) => ({
  id: `group-message-${id}`, recalled: false, text: `内容 ${id}`, ...overrides,
});

test("a translation stays a draft until the reader applies it", async () => {
  login();
  let body;
  globalThis.fetch = async (url, init) => {
    body = JSON.parse(init.body);
    return Response.json({ sourceMessageId: 6, targetLanguage: "en", text: "hello" });
  };
  const d = deps();
  const translation = useGroupTranslation(d);

  await translation.request(message(6));

  assert.deepEqual(body, { targetLanguage: "en" });
  assert.equal(translation.preview.value?.text, "hello");
  assert.equal(translation.draft.value, "hello");
  assert.equal(translation.busy.value, null);
  assert.deepEqual(d.state.notes, [], "nothing is sent or announced on preview");
});

test("switching conversation mid-flight discards the answer", async () => {
  login();
  let release;
  globalThis.fetch = () => new Promise((resolve) => { release = resolve; });
  let active = "server-group-5";
  const d = deps({ activeId: () => active });
  const translation = useGroupTranslation(d);

  const pending = translation.request(message(6));
  active = "server-group-9";
  release(Response.json({ sourceMessageId: 6, targetLanguage: "en", text: "late" }));
  await pending;

  assert.equal(translation.preview.value, null);
  assert.equal(translation.draft.value, "");
});

test("applying a draft concatenates the composer and refuses an oversized result", async () => {
  login();
  globalThis.fetch = async () => Response.json({ sourceMessageId: 6, targetLanguage: "en", text: "hello" });
  const composer = ref("先说的话");
  const d = deps({ composer });
  const translation = useGroupTranslation(d);

  await translation.request(message(6));
  translation.useDraft();

  assert.equal(composer.value, "先说的话\nhello");
  assert.equal(translation.preview.value, null);
  assert.deepEqual(d.state.notes, ["译文已填入输入框，请检查后自行发送"]);

  globalThis.fetch = async () => Response.json({
    sourceMessageId: 6, targetLanguage: "en", text: "x".repeat(4000),
  });
  await translation.request(message(6));
  translation.useDraft();

  assert.equal(translation.preview.value?.text.length, 4000, "the preview survives the refusal");
  assert.equal(d.state.notes.at(-1), "输入框内容超过 4000 字，请先精简译文");
});

test("a recall clears the preview it belongs to", async () => {
  login();
  globalThis.fetch = async () => Response.json({ sourceMessageId: 6, targetLanguage: "en", text: "hello" });
  const d = deps();
  const translation = useGroupTranslation(d);

  await translation.request(message(6));
  translation.onRecalled(7);
  assert.equal(translation.preview.value?.text, "hello");

  translation.onRecalled(6);
  assert.equal(translation.preview.value, null);
  assert.equal(translation.forId.value, null);
});

test("a recalled message is never sent to the translator", async () => {
  login();
  let calls = 0;
  globalThis.fetch = async () => { calls++; return Response.json({}); };
  const d = deps();
  const translation = useGroupTranslation(d);

  await translation.request(message(6, { recalled: true }));

  assert.equal(calls, 0);
  assert.equal(translation.preview.value, null);
});
