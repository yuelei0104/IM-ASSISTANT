import { demoCodes, type ContactType } from "./demoVerification";
export type { ContactType } from "./demoVerification";

export type DemoAccount = {
  id: string;
  name: string;
  handle: string;
  phone?: string;
  email?: string;
  salt: string;
  passwordHash: string;
};

const accountKey = "im-assistant-demo-accounts-v1";
export const sessionKey = "im-assistant-demo-account-id-v1";

export function accountStateKey(accountId: string): string {
  return `im-assistant-demo-state-${accountId}`;
}

export function accounts(): DemoAccount[] {
  try {
    const saved = JSON.parse(localStorage.getItem(accountKey) || "[]");
    return Array.isArray(saved)
      ? saved
          .filter(
            (item) =>
              typeof item.id === "string" &&
              typeof item.passwordHash === "string" &&
              typeof item.salt === "string",
          )
          .map((item): DemoAccount => ({
            id: item.id,
            name: item.name,
            handle: item.handle,
            phone:
              item.phone ||
              (item.contactType === "phone" ? item.contact : undefined),
            email:
              item.email ||
              (item.contactType === "email" ? item.contact : undefined),
            salt: item.salt,
            passwordHash: item.passwordHash,
          }))
          .filter((item) => item.phone || item.email)
      : [];
  } catch {
    return [];
  }
}

export function normalizeContact(type: ContactType, value: string): string {
  return type === "email"
    ? value.trim().toLowerCase()
    : value.trim().replace(/[ -]/g, "");
}

export function validContact(type: ContactType, value: string): boolean {
  const normalized = normalizeContact(type, value);
  return type === "phone"
    ? /^1[3-9]\d{9}$/.test(normalized)
    : /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalized) && normalized.length <= 254;
}

export function validPassword(value: string): boolean {
  return (
    value.length >= 8 &&
    value.length <= 72 &&
    /[a-zA-Z]/.test(value) &&
    /\d/.test(value)
  );
}

function hex(bytes: Uint8Array): string {
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join(
    "",
  );
}

async function hashPassword(password: string, salt: string): Promise<string> {
  if (!globalThis.crypto?.subtle)
    throw new Error("演示账号需要在 localhost 或 HTTPS 环境下使用");
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(password),
    "PBKDF2",
    false,
    ["deriveBits"],
  );
  const saltBytes = Uint8Array.from(salt.match(/.{2}/g) || [], (part) =>
    parseInt(part, 16),
  );
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", salt: saltBytes, iterations: 150_000, hash: "SHA-256" },
    key,
    256,
  );
  return hex(new Uint8Array(bits));
}

export type RegistrationInput = {
  name: string;
  handle: string;
  phone?: string;
  email?: string;
  phoneCode?: string;
  emailCode?: string;
  password: string;
};

export function validateRegistration(input: RegistrationInput) {
  const name = input.name.trim();
  const handle = input.handle.trim().toLowerCase();
  const phone = normalizeContact("phone", input.phone || "");
  const email = normalizeContact("email", input.email || "");
  if (!name || name.length > 24) throw new Error("昵称应为 1–24 个字符");
  if (!/^[a-z0-9_]{3,20}$/.test(handle))
    throw new Error("唯一账号需为 3–20 位字母、数字或下划线");
  if (!phone && !email) throw new Error("请至少填写手机号或邮箱");
  if (phone && !validContact("phone", phone))
    throw new Error("请输入正确的中国大陆手机号");
  if (email && !validContact("email", email))
    throw new Error("请输入正确的邮箱地址");
  if (!validPassword(input.password))
    throw new Error("密码需为 8–72 位，并包含字母和数字");
  const existing = accounts();
  if (phone && existing.some((account) => account.phone === phone))
    throw new Error("此手机号已绑定其他账号");
  if (email && existing.some((account) => account.email === email))
    throw new Error("此邮箱已绑定其他账号");
  if (existing.some((account) => account.handle === handle))
    throw new Error("此唯一账号已被使用");
  return { name, handle, phone, email };
}

export async function registerAccount(
  input: RegistrationInput,
): Promise<DemoAccount> {
  const { name, handle, phone, email } = validateRegistration(input);
  const existing = accounts();
  if (phone)
    demoCodes.verify("register", "phone", phone, input.phoneCode || "");
  if (email)
    demoCodes.verify("register", "email", email, input.emailCode || "");
  const salt = hex(crypto.getRandomValues(new Uint8Array(16)));
  const account: DemoAccount = {
    id: crypto.randomUUID(),
    name,
    handle,
    ...(phone ? { phone } : {}),
    ...(email ? { email } : {}),
    salt,
    passwordHash: await hashPassword(input.password, salt),
  };
  localStorage.setItem(accountKey, JSON.stringify([...existing, account]));
  if (phone) demoCodes.consume("register", "phone", phone);
  if (email) demoCodes.consume("register", "email", email);
  return account;
}

export async function authenticateAccount(
  contactType: ContactType,
  identifier: string,
  password: string,
): Promise<DemoAccount | null> {
  const contact = normalizeContact(contactType, identifier);
  const account = accounts().find((item) => item[contactType] === contact);
  if (!account || !password) return null;
  const candidate = await hashPassword(password, account.salt);
  // This is a local UI demonstration; authentication must be performed by the backend.
  return candidate === account.passwordHash ? account : null;
}

export function authenticateWithCode(
  contactType: ContactType,
  identifier: string,
  code: string,
): DemoAccount | null {
  const contact = normalizeContact(contactType, identifier);
  if (!validContact(contactType, contact))
    throw new Error("请输入正确的手机号或邮箱");
  demoCodes.verify("login", contactType, contact, code);
  demoCodes.consume("login", contactType, contact);
  return accounts().find((item) => item[contactType] === contact) || null;
}

export async function bindAccountContact(
  accountId: string,
  contactType: ContactType,
  identifier: string,
  password: string,
  code: string,
): Promise<DemoAccount> {
  const contact = normalizeContact(contactType, identifier);
  if (!validContact(contactType, contact))
    throw new Error(
      contactType === "phone"
        ? "请输入正确的中国大陆手机号"
        : "请输入正确的邮箱地址",
    );
  const existing = accounts();
  const index = existing.findIndex((item) => item.id === accountId);
  if (index < 0) throw new Error("账号不存在，请重新登录");
  const account = existing[index]!;
  if (account[contactType]) throw new Error("此账号已经绑定了该联系方式");
  if (existing.some((item) => item[contactType] === contact))
    throw new Error("此手机号或邮箱已绑定其他账号");
  const verified = await hashPassword(password, account.salt);
  if (verified !== account.passwordHash) throw new Error("密码不正确");
  demoCodes.verify("bind", contactType, contact, code);
  const updated = { ...account, [contactType]: contact };
  existing[index] = updated;
  localStorage.setItem(accountKey, JSON.stringify(existing));
  demoCodes.consume("bind", contactType, contact);
  return updated;
}

export function sessionAccount(): DemoAccount | null {
  try {
    const id = sessionStorage.getItem(sessionKey);
    return accounts().find((account) => account.id === id) || null;
  } catch {
    return null;
  }
}
