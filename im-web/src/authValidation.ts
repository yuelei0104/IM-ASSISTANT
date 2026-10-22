export type ContactType = "phone" | "email";

export type RegistrationInput = {
  name: string;
  handle: string;
  phone?: string;
  email?: string;
  phoneCode?: string;
  emailCode?: string;
  password: string;
};

export function normalizeContact(type: ContactType, value: string): string {
  return type === "email"
    ? value.trim().toLowerCase()
    : value.trim().replace(/[ -]/g, "");
}

export function validContact(type: ContactType, value: string): boolean {
  const normalized = normalizeContact(type, value);
  return type === "phone"
    ? /^1[3-9]\d{9}$/.test(normalized)
    : /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalized) &&
        normalized.length <= 254;
}

export function validPassword(value: string): boolean {
  return (
    value.length >= 8 &&
    value.length <= 72 &&
    /[a-zA-Z]/.test(value) &&
    /\d/.test(value)
  );
}

// The backend checks uniqueness and verifies codes when registration is submitted.
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
  return { name, handle, phone, email };
}
