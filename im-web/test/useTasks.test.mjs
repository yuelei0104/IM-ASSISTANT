import test from "node:test";
import assert from "node:assert/strict";
import { sessionKey } from "../src/session.ts";
import { useTasks } from "../src/useTasks.ts";

function deps(overrides = {}) {
  const opened = [];
  const notices = [];
  return {
    opened,
    notices,
    accountId: () => 7,
    activeGroupId: () => 4,
    openTaskPage: () => opened.push("tasks"),
    notify: (message) => notices.push(message),
    revealSource: async () => {},
    ...overrides,
  };
}

test("a group message only stages a draft the user still has to confirm", () => {
  const d = deps();
  const tasks = useTasks(d);
  tasks.stageFromMessage({ id: "group-message-8", text: "  明天发版  ", recalled: false });

  assert.deepEqual(tasks.source.value, { sourceGroupId: 4, sourceMessageId: 8 });
  assert.equal(tasks.draft.value, "明天发版");
  assert.deepEqual(d.opened, ["tasks"]);
  assert.match(d.notices[0], /点击添加后才会保存/);
});

test("staging ignores recalled messages and non-group conversations", () => {
  const tasks = useTasks(deps());
  tasks.stageFromMessage({ id: "group-message-8", text: "已撤回", recalled: true });
  assert.equal(tasks.source.value, null);
  tasks.stageFromMessage({ id: "server-message-8", text: "私聊", recalled: false });
  assert.equal(tasks.source.value, null);
  assert.equal(tasks.draft.value, "");
});

test("adding a task posts the reviewed title plus the source identifiers", async () => {
  globalThis.sessionStorage = { getItem: (key) => (key === sessionKey ? "token" : null) };
  const posted = [];
  globalThis.fetch = async (url, options) => {
    if (options.method === "POST") {
      posted.push(JSON.parse(options.body));
      return Response.json({ id: 9, title: "确认后的标题", completed: false,
        sourceGroupId: 4, sourceMessageId: 8 }, { status: 201 });
    }
    return Response.json({ items: [], nextBeforeId: null, openCount: 1, completedCount: 0 });
  };
  const tasks = useTasks(deps());
  tasks.stageFromMessage({ id: "group-message-8", text: "原始消息内容", recalled: false });
  tasks.draft.value = "确认后的标题";
  await tasks.add();

  assert.deepEqual(posted, [{ title: "确认后的标题", sourceGroupId: 4, sourceMessageId: 8 }]);
  assert.equal(tasks.draft.value, "");
  assert.equal(tasks.source.value, null);
});

test("a stale task response cannot land after the account is reset", async () => {
  globalThis.sessionStorage = { getItem: (key) => (key === sessionKey ? "token" : null) };
  let release;
  globalThis.fetch = () => new Promise((resolve) => {
    release = () => resolve(Response.json({ items: [{ id: 3, title: "上一个账号", completed: false }],
      nextBeforeId: null, openCount: 1, completedCount: 0 }));
  });
  const tasks = useTasks(deps());
  const pending = tasks.refresh();
  tasks.reset();
  release();
  await pending;

  assert.deepEqual(tasks.items.value, []);
  assert.equal(tasks.openCount.value, 0);
  assert.equal(tasks.loading.value, false);
});
