import test from "node:test";
import assert from "node:assert/strict";
import { directPeer, serverGroupId } from "../src/chatIds.ts";

test("a conversation id resolves to the server peer it was built from", () => {
  assert.equal(directPeer("server-user-3"), 3);
  assert.equal(directPeer("server-user-1042"), 1042);
});

test("anything that is not a server id stays unresolved", () => {
  for (const id of ["", "server-user-0", "server-user-03", "server-user-", "user-3",
                    "server-group-3", "server-user-3x"]) {
    assert.equal(directPeer(id), null, id);
  }
});

test("groups and direct chats never collide", () => {
  assert.equal(serverGroupId("server-group-5"), 5);
  assert.equal(serverGroupId("server-user-5"), null);
  assert.equal(directPeer("server-group-5"), null);
  assert.equal(serverGroupId("server-group-"), null);
});
