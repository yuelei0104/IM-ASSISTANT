import test from "node:test";
import assert from "node:assert/strict";
import { sessionKey } from "../src/authApi.ts";
import { listTasks, createTask, updateTaskDetails, completeTask, deleteTask } from "../src/taskApi.ts";

test("personal task requests stay authenticated and preserve user edits", async () => {
  globalThis.sessionStorage = { getItem: (key) => key === sessionKey ? "my-token" : null };
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    assert.equal(options.headers.Authorization, "Bearer my-token");
    if (options.method === "DELETE") return new Response(null, { status: 204 });
    return Response.json(url.includes("?limit")
      ? { items: [], nextBeforeId: null, openCount: 0, completedCount: 0 }
      : { id: 8, title: "跟进会议", completed: false });
  };
  await listTasks(20);
  await createTask("跟进会议");
  await updateTaskDetails(8, "改好标题", null);
  await completeTask(8, true);
  await deleteTask(8);
  assert.equal(calls[0].url, "/api/chats/tasks?limit=50&beforeId=20");
  assert.deepEqual(JSON.parse(calls[1].options.body), { title: "跟进会议" });
  assert.equal(calls[2].options.method, "PUT");
  assert.deepEqual(JSON.parse(calls[2].options.body), { title: "改好标题", dueDate: null });
  assert.equal(calls[3].options.method, "PATCH");
  assert.deepEqual(JSON.parse(calls[3].options.body), { completed: true });
  assert.equal(calls[4].options.method, "DELETE");
});

test("new task accepts an optional local due date", async () => {
  globalThis.sessionStorage = { getItem: (key) => key === sessionKey ? "my-token" : null };
  globalThis.fetch = async (_, options) => {
    assert.deepEqual(JSON.parse(options.body), { title: "月底检查", dueDate: "2026-11-30" });
    return Response.json({ id: 9, title: "月底检查", dueDate: "2026-11-30" }, { status: 201 });
  };
  assert.equal((await createTask("月底检查", undefined, "2026-11-30")).dueDate, "2026-11-30");
});

test("message task sends only reviewed title and source identifiers", async () => {
  globalThis.sessionStorage = { getItem: (key) => key === sessionKey ? "my-token" : null };
  globalThis.fetch = async (url, options) => {
    assert.equal(url, "/api/chats/tasks");
    assert.equal(options.method, "POST");
    assert.deepEqual(JSON.parse(options.body), {
      title: "确认后的标题", sourceGroupId: 4, sourceMessageId: 8,
    });
    return Response.json({ id: 7, title: "确认后的标题", completed: false,
      sourceGroupId: 4, sourceMessageId: 8 }, { status: 201 });
  };
  const saved = await createTask("确认后的标题", { sourceGroupId: 4, sourceMessageId: 8 });
  assert.equal(saved.sourceMessageId, 8);
});

test("personal task calls without a session never reach the server", async () => {
  globalThis.sessionStorage = { getItem: () => null };
  globalThis.fetch = () => { throw new Error("unexpected request"); };
  await assert.rejects(createTask("私人内容"), /请先登录/);
});
