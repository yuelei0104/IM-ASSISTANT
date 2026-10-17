export type ContactType = "phone" | "email";

export type DemoAccount = {
  id: string;
  name: string;
  handle: string;
  contactType: ContactType;
  contact: string;
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
      ? saved.filter(
          (item): item is DemoAccount =>
            typeof item.id === "string" &&
            typeof item.contact === "string" &&
            typeof item.passwordHash === "string",
        )
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

export async function registerAccount(input: {
  name: string;
  handle: string;
  contactType: ContactType;
  contact: string;
  password: string;
}): Promise<DemoAccount> {
  const name = input.name.trim();
  const handle = input.handle.trim().toLowerCase();
  const contact = normalizeContact(input.contactType, input.contact);
  if (!name || name.length > 24) throw new Error("昵称应为 1–24 个字符");
  if (!/^[a-z0-9_]{3,20}$/.test(handle))
    throw new Error("唯一账号需为 3–20 位字母、数字或下划线");
  if (!validContact(input.contactType, contact))
    throw new Error(
      input.contactType === "phone"
        ? "请输入正确的中国大陆手机号"
        : "请输入正确的邮箱地址",
    );
  if (!validPassword(input.password))
    throw new Error("密码需为 8–72 位，并包含字母和数字");
  const existing = accounts();
  if (
    existing.some(
      (account) =>
        account.contactType === input.contactType &&
        account.contact === contact,
    )
  )
    throw new Error("此手机号或邮箱已注册");
  if (existing.some((account) => account.handle === handle))
    throw new Error("此唯一账号已被使用");
  const salt = hex(crypto.getRandomValues(new Uint8Array(16)));
  const account: DemoAccount = {
    id: crypto.randomUUID(),
    name,
    handle,
    contactType: input.contactType,
    contact,
    salt,
    passwordHash: await hashPassword(input.password, salt),
  };
  localStorage.setItem(accountKey, JSON.stringify([...existing, account]));
  return account;
}

export async function authenticateAccount(
  contactType: ContactType,
  identifier: string,
  password: string,
): Promise<DemoAccount | null> {
  const contact = normalizeContact(contactType, identifier);
  const account = accounts().find(
    (item) => item.contactType === contactType && item.contact === contact,
  );
  if (!account || !password) return null;
  const candidate = await hashPassword(password, account.salt);
  // This is a local UI demonstration; authentication must be performed by the backend.
  return candidate === account.passwordHash ? account : null;
}

export function sessionAccount(): DemoAccount | null {
  try {
    const id = sessionStorage.getItem(sessionKey);
    return accounts().find((account) => account.id === id) || null;
  } catch {
    return null;
  }
}
