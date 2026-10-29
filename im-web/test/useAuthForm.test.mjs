import test from "node:test";
import assert from "node:assert/strict";
import { onSessionExpired, sessionKey } from "../src/session.ts";
import { useAuthForm } from "../src/useAuthForm.ts";

function login() {
  const store = new Map([[sessionKey, "token"]]);
  globalThis.sessionStorage = {
    getItem: (key) => store.get(key) ?? null,
    setItem: (key, value) => store.set(key, value),
    removeItem: (key) => store.delete(key),
  };
  return store;
}

function deps(overrides = {}) {
  const signIns = [];
  const updates = [];
  return {
    signIns,
    updates,
    hasAccount: () => true,
    missingContact: () => "email",
    signedIn: (account) => signIns.push(account),
    accountUpdated: (account) => updates.push(account),
    ...overrides,
  };
}

test("registering stops at the verification step before it creates the account", async () => {
  login();
  const posted = [];
  globalThis.fetch = async (url, options) => {
    posted.push(url);
    if (url.endsWith("/registration-check")) return Response.json({}, { status: 202 });
    return Response.json({ id: 1, nickname: "张三", handle: "zhangsan", phone: null, email: null });
  };
  const d = deps();
  const auth = useAuthForm(d);
  auth.switchMode("register");
  auth.name.value = "张三";
  auth.handle.value = "zhangsan";
  auth.phone.value = "13800138000";
  auth.password.value = "abc12345";
  auth.confirm.value = "abc12345";
  await auth.submit();

  assert.deepEqual(posted, ["/api/auth/registration-check"]);
  assert.equal(auth.step.value, "verify");
  assert.equal(auth.pending.value.handle, "zhangsan");
  assert.deepEqual(d.signIns, []);
  assert.equal(auth.busy.value, false);
});

test("a mismatched confirmation never reaches the network", async () => {
  login();
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    return Response.json({});
  };
  const auth = useAuthForm(deps());
  auth.switchMode("register");
  auth.phone.value = "13800138000";
  auth.password.value = "abc12345";
  auth.confirm.value = "abc12346";
  await auth.submit();

  assert.equal(calls, 0);
  assert.match(auth.error.value, /不一致/);
});

test("a wrong password while binding keeps the session and only fills the bind error", async () => {
  const store = login();
  let expired = 0;
  const stop = onSessionExpired(() => expired++);
  globalThis.fetch = async () => Response.json({ message: "密码不正确" }, { status: 401 });
  const d = deps();
  const auth = useAuthForm(d);
  auth.bindValue.value = "me@example.com";
  auth.bindPassword.value = "wrong-password";
  auth.bindCode.value = "123456";
  await auth.bind();
  stop();

  assert.equal(auth.bindError.value, "密码不正确");
  assert.equal(store.get(sessionKey), "token");
  assert.equal(expired, 0);
  assert.deepEqual(d.updates, []);
  assert.equal(auth.bindBusy.value, false);
});

test("binding the missing contact hands the updated profile back", async () => {
  login();
  globalThis.fetch = async () => Response.json({
    id: 7, nickname: "张三", handle: "zhangsan", phone: "13800138000", email: "me@example.com",
  });
  const d = deps();
  const auth = useAuthForm(d);
  auth.bindValue.value = " me@example.com ";
  auth.bindPassword.value = "abc12345";
  auth.bindCode.value = "123456";
  await auth.bind();

  assert.equal(d.updates.length, 1);
  assert.equal(d.updates[0].email, "me@example.com");
  assert.match(auth.bindSuccess.value, /邮箱已绑定/);
  assert.equal(auth.bindValue.value, "");
});

test("a successful login hands the account over and clears the password", async () => {
  login();
  globalThis.fetch = async () => Response.json({
    token: "fresh-token",
    account: { id: 7, nickname: "张三", handle: "zhangsan", phone: "13800138000", email: null },
  });
  const d = deps();
  const auth = useAuthForm(d);
  auth.contactType.value = "phone";
  auth.identifier.value = "13800138000";
  auth.password.value = "abc12345";
  await auth.submit();

  assert.equal(d.signIns.length, 1);
  assert.equal(d.signIns[0].id, 7);
  assert.equal(auth.password.value, "");
  assert.equal(auth.busy.value, false);
});

test("switching modes clears secrets and pending registration", async () => {
  login();
  const auth = useAuthForm(deps());
  auth.switchMode("register");
  auth.password.value = "abc12345";
  auth.confirm.value = "abc12345";
  auth.phoneCode.value = "123456";
  auth.error.value = "旧错误";
  auth.switchMode("login");

  assert.equal(auth.mode.value, "login");
  assert.equal(auth.step.value, "details");
  assert.equal(auth.pending.value, null);
  assert.equal(auth.password.value, "");
  assert.equal(auth.phoneCode.value, "");
  assert.equal(auth.error.value, "");
});

test("the countdown stops when the form is disposed", () => {
  login();
  const auth = useAuthForm(deps());
  auth.dispose();
  assert.equal(typeof auth.secondsLeft("login", "phone", "13800138000"), "number");
});
