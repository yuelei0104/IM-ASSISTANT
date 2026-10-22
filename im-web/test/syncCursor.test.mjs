import test from "node:test";
import assert from "node:assert/strict";
import { resumeCursor, savedCursor, saveCursor } from "../src/syncCursor.ts";
import { syncForward } from "../src/incrementalSync.ts";

function memory() {
  const entries = new Map();
  return {
    getItem: (key) => entries.get(key) ?? null,
    setItem: (key, value) => entries.set(key, value),
  };
}

test("cursors survive reload and login and stay scoped to each user and conversation", () => {
  globalThis.localStorage = memory();
  globalThis.sessionStorage = memory();
  saveCursor(1, "direct", 2, 100);
  saveCursor(1, "group", 2, 40);
  assert.equal(savedCursor(1, "direct", 2), 100);
  assert.equal(savedCursor(1, "group", 2), 40);
  assert.equal(savedCursor(2, "direct", 2), null);
  assert.equal(savedCursor(1, "direct", 3), null);

  globalThis.sessionStorage = memory(); // New tab after the first one closes.
  assert.equal(savedCursor(1, "direct", 2), 100);
  saveCursor(1, "direct", 2, 130);
  assert.equal(savedCursor(1, "direct", 2), 130);
  assert.equal(resumeCursor(savedCursor(1, "direct", 2), 155), 130);
  assert.equal(resumeCursor(null, 155), 155);
  assert.equal(resumeCursor(130, 12), 12); // Database recreated.
});

test("an older tab cursor wins over the shared cursor and blocked storage is harmless", () => {
  globalThis.sessionStorage = memory();
  globalThis.localStorage = memory();
  saveCursor(1, "direct", 2, 10);
  globalThis.localStorage.setItem("im-assistant:sync:v1:1:direct:2", "100");
  assert.equal(savedCursor(1, "direct", 2), 10);
  globalThis.sessionStorage.setItem("im-assistant:sync:v1:1:direct:2", "-3");
  assert.equal(savedCursor(1, "direct", 2), 100);
  globalThis.localStorage = { getItem: () => { throw Error("blocked"); }, setItem: () => { throw Error("blocked"); } };
  assert.equal(savedCursor(1, "direct", 2), null);
  assert.doesNotThrow(() => saveCursor(1, "direct", 2, 110));
  assert.equal(savedCursor(1, "direct", 2), 110);
});

test("a reopened conversation fills more than one page after its saved cursor", async () => {
  globalThis.localStorage = memory();
  globalThis.sessionStorage = memory();
  saveCursor(7, "group", 3, 10);
  globalThis.sessionStorage = memory();
  const newest = Array.from({ length: 50 }, (_, i) => ({ id: i + 37 }));
  const displayed = new Map(newest.map((message) => [message.id, message]));
  const pages = [];
  const reached = await syncForward(resumeCursor(savedCursor(7, "group", 3), 86), async (cursor) => {
    pages.push(cursor);
    const messages = Array.from({ length: 86 - cursor }, (_, i) => ({ id: cursor + i + 1 })).slice(0, 50);
    return { messages, nextAfterId: cursor + messages.length < 86 ? messages.at(-1).id : null };
  }, (messages, cursor) => {
    for (const message of messages) displayed.set(message.id, message);
    saveCursor(7, "group", 3, cursor);
    return true;
  });
  assert.deepEqual(pages, [10, 60]);
  assert.equal(reached, 86);
  assert.equal(displayed.size, 76);
  assert.equal(savedCursor(7, "group", 3), 86);
});
