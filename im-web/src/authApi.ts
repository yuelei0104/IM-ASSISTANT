import type { ContactType, RegistrationInput } from "./authValidation";
import { requestJson, ApiError } from "./apiClient";
import { sessionKey, sessionToken, clearSession } from "./session";
export { sessionKey, sessionToken, clearSession, ApiError };

export type CodePurpose = "register" | "login" | "bind" | "reset";

export type Account = {
  id: number;
  name: string;
  handle: string;
  phone?: string;
  email?: string;
};

type ServerAccount = {
  id: number;
  nickname: string;
  handle: string;
  phone: string | null;
  email: string | null;
};

type LoginResponse = { token: string; account: ServerAccount };
export type CodeResponse = {
  expiresInSeconds: number;
  retryAfterSeconds: number;
  devCode: string | null;
};

const apiBase = (import.meta.env?.VITE_API_BASE_URL || "/api").replace(
  /\/$/,
  "",
);

export function accountStateKey(id: number): string {
  return `im-assistant-local-state-user-${id}`;
}

function account(value: ServerAccount): Account {
  return {
    id: value.id,
    name: value.nickname,
    handle: value.handle,
    ...(value.phone ? { phone: value.phone } : {}),
    ...(value.email ? { email: value.email } : {}),
  };
}

async function request<T>(
  path: string,
  body?: object,
  token?: string,
): Promise<T> {
  return requestJson<T>(`${apiBase}/auth${path}`, {
    method: body === undefined ? "GET" : "POST", body, token: token ?? null,
    requireAuth: false,
    fallback: () => "操作失败，请稍后重试",
  });
}

function requireSession(): string {
  const token = sessionToken();
  if (!token) throw new ApiError("请先登录", 401);
  return token;
}

export async function sendCode(
  purpose: CodePurpose,
  contactType: ContactType,
  contact: string,
): Promise<CodeResponse> {
  return request<CodeResponse>(
    "/codes",
    { purpose, contactType, contact },
    purpose === "bind" ? requireSession() : undefined,
  );
}

export async function register(input: RegistrationInput): Promise<Account> {
  const result = await request<ServerAccount>("/register", {
    nickname: input.name,
    handle: input.handle,
    phone: input.phone || null,
    email: input.email || null,
    password: input.password,
    phoneCode: input.phoneCode || null,
    emailCode: input.emailCode || null,
  });
  return account(result);
}

export async function checkRegistration(
  input: RegistrationInput,
): Promise<void> {
  await request<void>("/registration-check", {
    nickname: input.name,
    handle: input.handle,
    phone: input.phone || null,
    email: input.email || null,
  });
}

async function saveLogin(result: LoginResponse): Promise<Account> {
  sessionStorage.setItem(sessionKey, result.token);
  return account(result.account);
}

export async function loginWithPassword(
  contactType: ContactType,
  contact: string,
  password: string,
): Promise<Account> {
  return saveLogin(
    await request<LoginResponse>("/login/password", {
      contactType,
      contact,
      password,
    }),
  );
}

export async function loginWithCode(
  contactType: ContactType,
  contact: string,
  code: string,
): Promise<Account> {
  return saveLogin(
    await request<LoginResponse>("/login/code", { contactType, contact, code }),
  );
}

export async function resetPassword(
  contactType: ContactType,
  contact: string,
  code: string,
  newPassword: string,
): Promise<void> {
  await request<void>("/password/reset", {
    contactType,
    contact,
    code,
    newPassword,
  });
}

export async function currentUser(): Promise<Account> {
  return account(
    await request<ServerAccount>("/me", undefined, requireSession()),
  );
}

export async function bindContact(
  contactType: ContactType,
  contact: string,
  password: string,
  code: string,
): Promise<Account> {
  return account(
    await request<ServerAccount>(
      "/bind",
      { contactType, contact, password, code },
      requireSession(),
    ),
  );
}

export async function logout(): Promise<void> {
  await request<void>("/logout", {}, requireSession());
  clearSession();
}
