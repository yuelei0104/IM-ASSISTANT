import test from "node:test";
import assert from "node:assert/strict";
import { syncForward } from "../src/incrementalSync.ts";

test("reconnect catches more than one page without gaps or duplicates", async () => {
  const ids = [];
  const cursors = [];
  const result = await syncForward(100, async (cursor) => {
    cursors.push(cursor);
    return cursor === 100
      ? { messages: [{ id: 101 }, { id: 103 }], nextAfterId: 103 }
      : { messages: [{ id: 105 }], nextAfterId: null };
  }, (messages) => { ids.push(...messages.map((m) => m.id)); return true; });
  assert.deepEqual(cursors, [100, 103]);
  assert.deepEqual(ids, [101, 103, 105]);
  assert.equal(result, 105);
});

test("failed page resumes at last applied cursor and a stale conversation does not merge", async () => {
  let cursor = 100;
  const apply = (messages, latest) => { cursor = latest; return true; };
  await assert.rejects(syncForward(cursor, async (from) => {
    if (from === 100) return { messages: [{ id: 101 }], nextAfterId: 101 };
    throw new Error("断网");
  }, apply), /断网/);
  assert.equal(cursor, 101);
  const fetched = [];
  assert.equal(await syncForward(cursor, async (from) => {
    fetched.push(from);
    return { messages: [{ id: 102 }], nextAfterId: null };
  }, apply), 102);
  assert.deepEqual(fetched, [101]);
  assert.equal(await syncForward(cursor, async () =>
    ({ messages: [{ id: 103 }], nextAfterId: null }), () => false), null);
  assert.equal(cursor, 102);
  await assert.rejects(syncForward(cursor, async () =>
    ({ messages: [{ id: 102 }], nextAfterId: 102 }), apply), /顺序无效/);
});
