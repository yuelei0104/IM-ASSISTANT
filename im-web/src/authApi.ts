import type { ContactType, RegistrationInput } from "./authValidation";

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

export const sessionKey = "im-assistant-server-token-v1";
const apiBase = (import.meta.env?.VITE_API_BASE_URL || "/api").replace(
  /\/$/,
  "",
);

export function accountStateKey(id: number): string {
  return `im-assistant-local-state-user-${id}`;
}

export function sessionToken(): string | null {
  try {
    return sessionStorage.getItem(sessionKey);
  } catch {
    return null;
  }
}

export function clearSession(): void {
  try {
    sessionStorage.removeItem(sessionKey);
  } catch {}
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

export class ApiError extends Error {
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.status = status;
  }
}

async function request<T>(
  path: string,
  body?: object,
  token?: string,
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${apiBase}/auth${path}`, {
      method: body === undefined ? "GET" : "POST",
      headers: {
        ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
    });
  } catch {
    throw new ApiError("无法连接用户服务，请确认后端已在 8081 端口启动", 0);
  }
  if (response.status === 204) return undefined as T;
  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    throw new ApiError(
      response.status >= 500
        ? "用户服务暂不可用，请检查后端是否已启动"
        : `用户服务返回 HTTP ${response.status}，响应格式无法识别；请检查浏览器请求来源与网关`,
      response.status,
    );
  }
  if (!response.ok) {
    const message = (payload as { message?: unknown } | null)?.message;
    throw new ApiError(
      typeof message === "string" ? message : "操作失败，请稍后重试",
      response.status,
    );
  }
  return payload as T;
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
