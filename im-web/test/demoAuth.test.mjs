import test from "node:test";
import assert from "node:assert/strict";
import {
  accountStateKey,
  accounts,
  authenticateAccount,
  normalizeContact,
  registerAccount,
  sessionAccount,
  sessionKey,
  validContact,
} from "../src/demoAuth.ts";

const items = new Map();
globalThis.localStorage = {
  getItem: (key) => items.get(key) ?? null,
  setItem: (key, value) => items.set(key, String(value)),
  removeItem: (key) => items.delete(key),
};
globalThis.sessionStorage = globalThis.localStorage;

test("phone and email registration require a separate password login", async () => {
  items.clear();
  assert.equal(validContact("phone", "138 0013 8000"), true);
  assert.equal(validContact("email", " Person@Example.COM "), true);
  assert.equal(validContact("phone", "12345"), false);
  assert.equal(
    normalizeContact("email", " Person@Example.COM "),
    "person@example.com",
  );

  const phone = await registerAccount({
    name: "岳蕾",
    handle: "yuelei",
    contactType: "phone",
    contact: "13800138000",
    password: "Testpass123",
  });
  assert.equal(sessionAccount(), null);
  assert.equal(
    await authenticateAccount("phone", phone.contact, "wrongpass"),
    null,
  );
  assert.equal(
    (await authenticateAccount("phone", phone.contact, "Testpass123"))?.id,
    phone.id,
  );
  assert.equal(
    await authenticateAccount("email", phone.contact, "Testpass123"),
    null,
  );
  assert.equal(JSON.stringify(accounts()).includes("Testpass123"), false);

  const email = await registerAccount({
    name: "林知夏",
    handle: "linzhixia",
    contactType: "email",
    contact: " Person@Example.COM ",
    password: "Anotherpass456",
  });
  assert.notEqual(accountStateKey(phone.id), accountStateKey(email.id));
  assert.equal(
    (await authenticateAccount("email", "person@example.com", "Anotherpass456"))
      ?.id,
    email.id,
  );
  localStorage.setItem(sessionKey, email.id);
  assert.equal(sessionAccount()?.id, email.id);
  await assert.rejects(
    registerAccount({
      name: "重复",
      handle: "new_handle",
      contactType: "email",
      contact: "person@example.com",
      password: "Anotherpass456",
    }),
    /已注册/,
  );
});
