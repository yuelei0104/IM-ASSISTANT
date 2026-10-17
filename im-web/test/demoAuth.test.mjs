import test from "node:test";
import assert from "node:assert/strict";
import {
  accountStateKey,
  accounts,
  authenticateAccount,
  bindAccountContact,
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

test("both contacts lead to one account and registration does not log in", async () => {
  items.clear();
  assert.equal(validContact("phone", "138 0013 8000"), true);
  assert.equal(
    normalizeContact("email", " Person@Example.COM "),
    "person@example.com",
  );
  assert.equal(validContact("phone", "12345"), false);
  const account = await registerAccount({
    name: "岳蕾",
    handle: "yuelei",
    phone: "13800138000",
    email: " Person@Example.COM ",
    password: "Testpass123",
  });
  assert.equal(sessionAccount(), null);
  assert.equal(account.email, "person@example.com");
  assert.equal(
    (await authenticateAccount("phone", account.phone, "Testpass123"))?.id,
    account.id,
  );
  assert.equal(
    (await authenticateAccount("email", account.email, "Testpass123"))?.id,
    account.id,
  );
  assert.equal(
    await authenticateAccount("phone", account.phone, "wrongpass"),
    null,
  );
  assert.equal(JSON.stringify(accounts()).includes("Testpass123"), false);
  localStorage.setItem(sessionKey, account.id);
  assert.equal(sessionAccount()?.id, account.id);
  await assert.rejects(
    registerAccount({
      name: "重复",
      handle: "another",
      email: "person@example.com",
      password: "Anotherpass456",
    }),
    /已绑定/,
  );
});

test("a phone-only account can bind email and then log in by either contact", async () => {
  items.clear();
  const account = await registerAccount({
    name: "林知夏",
    handle: "linzhixia",
    phone: "13900139000",
    password: "Anotherpass456",
  });
  assert.equal(
    await authenticateAccount("email", "lin@example.com", "Anotherpass456"),
    null,
  );
  await assert.rejects(
    bindAccountContact(account.id, "email", "lin@example.com", "wrongpass"),
    /密码不正确/,
  );
  const updated = await bindAccountContact(
    account.id,
    "email",
    " Lin@Example.COM ",
    "Anotherpass456",
  );
  assert.equal(updated.id, account.id);
  assert.equal(
    (await authenticateAccount("email", "lin@example.com", "Anotherpass456"))
      ?.id,
    account.id,
  );
  assert.equal(
    (await authenticateAccount("phone", "13900139000", "Anotherpass456"))?.id,
    account.id,
  );
  await assert.rejects(
    bindAccountContact(
      account.id,
      "email",
      "other@example.com",
      "Anotherpass456",
    ),
    /已经绑定/,
  );
  const second = await registerAccount({
    name: "何雨桐",
    handle: "heyutong",
    email: "he@example.com",
    password: "Password789",
  });
  assert.notEqual(accountStateKey(account.id), accountStateKey(second.id));
  await assert.rejects(
    bindAccountContact(second.id, "phone", "13900139000", "Password789"),
    /其他账号/,
  );
});

test("earlier single-contact demo accounts stay usable", async () => {
  items.clear();
  const account = await registerAccount({
    name: "旧用户",
    handle: "old_user",
    email: "old@example.com",
    password: "Password123",
  });
  localStorage.setItem(
    "im-assistant-demo-accounts-v1",
    JSON.stringify([
      {
        id: account.id,
        name: account.name,
        handle: account.handle,
        contactType: "email",
        contact: account.email,
        salt: account.salt,
        passwordHash: account.passwordHash,
      },
    ]),
  );
  assert.equal(
    (await authenticateAccount("email", "old@example.com", "Password123"))?.id,
    account.id,
  );
  assert.equal(
    (
      await bindAccountContact(
        account.id,
        "phone",
        "13800138000",
        "Password123",
      )
    ).phone,
    "13800138000",
  );
});
