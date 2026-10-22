import test from "node:test";
import assert from "node:assert/strict";
import {
  bindContact,
  checkRegistration,
  currentUser,
  loginWithPassword,
  logout,
  register,
  resetPassword,
  sendCode,
  sessionKey,
  sessionToken,
} from "../src/authApi.ts";
import { validateRegistration } from "../src/authValidation.ts";

const store = new Map();
globalThis.sessionStorage = {
  getItem: (key) => store.get(key) ?? null,
  setItem: (key, value) => store.set(key, String(value)),
  removeItem: (key) => store.delete(key),
};

const backendAccount = {
  id: 21,
  nickname: "岳蕾",
  handle: "yuelei_21",
  phone: "13800138000",
  email: "yuelei@example.com",
};

test("registration verifies both contacts through the server and login keeps a bearer session", async () => {
  store.clear();
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    if (url.endsWith("/registration-check"))
      return new Response(null, { status: 204 });
    if (url.endsWith("/codes"))
      return Response.json({
        devCode: "123456",
        expiresInSeconds: 300,
        retryAfterSeconds: 60,
      });
    if (url.endsWith("/register"))
      return Response.json(backendAccount, { status: 201 });
    if (url.endsWith("/login/password"))
      return Response.json({ token: "server-token", account: backendAccount });
    if (url.endsWith("/me")) return Response.json(backendAccount);
    if (url.endsWith("/bind")) return Response.json(backendAccount);
    if (url.endsWith("/logout")) return new Response(null, { status: 204 });
    throw new Error(`Unexpected endpoint ${url}`);
  };

  const input = {
    name: "岳蕾",
    handle: "yuelei_21",
    phone: "13800138000",
    email: "yuelei@example.com",
    password: "Password123",
    phoneCode: "123456",
    emailCode: "654321",
  };
  assert.deepEqual(validateRegistration(input), {
    name: input.name,
    handle: input.handle,
    phone: input.phone,
    email: input.email,
  });
  await checkRegistration(input);
  assert.deepEqual(JSON.parse(calls[0].options.body), {
    nickname: input.name,
    handle: input.handle,
    phone: input.phone,
    email: input.email,
  });
  assert.equal(
    (await sendCode("register", "phone", input.phone)).devCode,
    "123456",
  );
  assert.equal(
    (await sendCode("register", "email", input.email)).devCode,
    "123456",
  );
  assert.deepEqual(await register(input), {
    id: 21,
    name: "岳蕾",
    handle: "yuelei_21",
    phone: input.phone,
    email: input.email,
  });
  assert.deepEqual(JSON.parse(calls[3].options.body), {
    nickname: "岳蕾",
    handle: "yuelei_21",
    phone: input.phone,
    email: input.email,
    password: "Password123",
    phoneCode: "123456",
    emailCode: "654321",
  });
  await loginWithPassword("email", input.email, input.password);
  assert.equal(sessionStorage.getItem(sessionKey), "server-token");
  assert.equal((await currentUser()).name, "岳蕾");
  assert.equal(
    calls.at(-1).options.headers.Authorization,
    "Bearer server-token",
  );
  await bindContact("phone", input.phone, input.password, "123456");
  assert.equal(
    calls.at(-1).options.headers.Authorization,
    "Bearer server-token",
  );
  await logout();
  assert.equal(sessionToken(), null);
});

test("server errors surface without creating a local account or token", async () => {
  store.clear();
  globalThis.fetch = async () =>
    Response.json({ message: "此邮箱已绑定其他账号" }, { status: 409 });
  await assert.rejects(
    checkRegistration({
      name: "重复",
      handle: "duplicate",
      email: "a@example.com",
      password: "Password123",
    }),
    /此邮箱已绑定其他账号/,
  );
  assert.equal(sessionToken(), null);
  globalThis.fetch = async () => {
    throw new Error("connection refused");
  };
  await assert.rejects(
    sendCode("login", "email", "a@example.com"),
    /无法连接用户服务/,
  );
});

test("password recovery requests a reset code and submits the new password", async () => {
  store.clear();
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    if (url.endsWith("/codes"))
      return Response.json({
        devCode: "123456",
        expiresInSeconds: 300,
        retryAfterSeconds: 60,
      });
    if (url.endsWith("/password/reset"))
      return new Response(null, { status: 204 });
    throw new Error(`Unexpected endpoint ${url}`);
  };
  await sendCode("reset", "email", "yuelei@example.com");
  await resetPassword(
    "email",
    "yuelei@example.com",
    "123456",
    "NewPassword123",
  );
  assert.deepEqual(JSON.parse(calls[0].options.body), {
    purpose: "reset",
    contactType: "email",
    contact: "yuelei@example.com",
  });
  assert.deepEqual(JSON.parse(calls[1].options.body), {
    contactType: "email",
    contact: "yuelei@example.com",
    code: "123456",
    newPassword: "NewPassword123",
  });
  assert.equal(calls[1].options.headers.Authorization, undefined);
  assert.equal(sessionToken(), null);
});
