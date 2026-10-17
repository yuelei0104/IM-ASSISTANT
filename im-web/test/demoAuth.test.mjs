import test from "node:test";
import assert from "node:assert/strict";
import {
  accountStateKey,
  accounts,
  authenticateAccount,
  authenticateWithCode,
  bindAccountContact,
  normalizeContact,
  registerAccount,
  sessionAccount,
  sessionKey,
  validContact,
  validateRegistration,
} from "../src/demoAuth.ts";
import { demoCodes, DemoVerification } from "../src/demoVerification.ts";

const items = new Map();
globalThis.localStorage = {
  getItem: (key) => items.get(key) ?? null,
  setItem: (key, value) => items.set(key, String(value)),
  removeItem: (key) => items.delete(key),
};
globalThis.sessionStorage = globalThis.localStorage;

async function registerWithCodes(input) {
  const phoneCode = input.phone
    ? demoCodes.issue(
        "register",
        "phone",
        normalizeContact("phone", input.phone),
      )
    : undefined;
  const emailCode = input.email
    ? demoCodes.issue(
        "register",
        "email",
        normalizeContact("email", input.email),
      )
    : undefined;
  return registerAccount({ ...input, phoneCode, emailCode });
}

test("both contacts require separate codes and log in to the same account", async () => {
  items.clear();
  assert.equal(validContact("phone", "138 0013 8000"), true);
  assert.equal(
    normalizeContact("email", " Person@Example.COM "),
    "person@example.com",
  );
  const input = {
    name: "岳蕾",
    handle: "yuelei",
    phone: "13800138000",
    email: " Person@Example.COM ",
    password: "Testpass123",
  };
  await assert.rejects(registerAccount(input), /验证码不存在/);
  const account = await registerWithCodes(input);
  assert.equal(sessionAccount(), null);
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
  const loginCode = demoCodes.issue("login", "email", account.email);
  assert.equal(
    authenticateWithCode("email", account.email, loginCode)?.id,
    account.id,
  );
  assert.throws(
    () => authenticateWithCode("email", account.email, loginCode),
    /不存在/,
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

test("registration details are checked before issuing codes or creating an account", async () => {
  items.clear();
  const draft = {
    name: " 张三 ",
    handle: "Zhang_San",
    phone: "138 0013 8000",
    email: " Zhang@Example.COM ",
    password: "Testpass123",
  };
  assert.deepEqual(validateRegistration(draft), {
    name: "张三",
    handle: "zhang_san",
    phone: "13800138000",
    email: "zhang@example.com",
  });
  assert.equal(accounts().length, 0);
  assert.throws(
    () => validateRegistration({ ...draft, phone: "bad" }),
    /手机号/,
  );
  assert.throws(
    () => validateRegistration({ ...draft, password: "short" }),
    /密码/,
  );
  const account = await registerWithCodes(draft);
  assert.equal(accounts().length, 1);
  assert.throws(() => validateRegistration(draft), /已绑定/);
  assert.equal(account.handle, "zhang_san");
});

test("binding a missing contact requires the current password and its code", async () => {
  items.clear();
  const account = await registerWithCodes({
    name: "林知夏",
    handle: "linzhixia",
    phone: "13900139000",
    password: "Anotherpass456",
  });
  const code = demoCodes.issue("bind", "email", "lin@example.com");
  await assert.rejects(
    bindAccountContact(
      account.id,
      "email",
      "lin@example.com",
      "wrongpass",
      code,
    ),
    /密码不正确/,
  );
  await assert.rejects(
    bindAccountContact(
      account.id,
      "email",
      "lin@example.com",
      "Anotherpass456",
      "000000" === code ? "111111" : "000000",
    ),
    /验证码不正确/,
  );
  const updated = await bindAccountContact(
    account.id,
    "email",
    " Lin@Example.COM ",
    "Anotherpass456",
    code,
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
      code,
    ),
    /已经绑定/,
  );
  const second = await registerWithCodes({
    name: "何雨桐",
    handle: "heyutong",
    email: "he@example.com",
    password: "Password789",
  });
  assert.notEqual(accountStateKey(account.id), accountStateKey(second.id));
  await assert.rejects(
    bindAccountContact(
      second.id,
      "phone",
      "13900139000",
      "Password789",
      "123456",
    ),
    /其他账号/,
  );
});

test("old single-contact accounts can still bind a second verified contact", async () => {
  items.clear();
  const account = await registerWithCodes({
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
  const code = demoCodes.issue("bind", "phone", "13800138000");
  assert.equal(
    (
      await bindAccountContact(
        account.id,
        "phone",
        "13800138000",
        "Password123",
        code,
      )
    ).phone,
    "13800138000",
  );
});

test("demo codes expire, have resend cooldown, and reject repeated guesses", () => {
  let now = 1000;
  const codes = new DemoVerification(() => now);
  const code = codes.issue("register", "phone", "13700137000");
  assert.equal(codes.remaining("register", "phone", "13700137000"), 60);
  assert.throws(
    () => codes.issue("register", "phone", "13700137000"),
    /60 秒后/,
  );
  for (let i = 0; i < 5; i++)
    assert.throws(
      () =>
        codes.verify(
          "register",
          "phone",
          "13700137000",
          "000000" === code ? "111111" : "000000",
        ),
      /不正确/,
    );
  assert.throws(
    () => codes.verify("register", "phone", "13700137000", code),
    /次数过多/,
  );
  now += 60_000;
  const next = codes.issue("register", "phone", "13700137000");
  now += 5 * 60_000;
  assert.throws(
    () => codes.verify("register", "phone", "13700137000", next),
    /已过期/,
  );
});
