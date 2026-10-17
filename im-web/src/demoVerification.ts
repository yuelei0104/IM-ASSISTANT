export type ContactType = "phone" | "email";
export type CodePurpose = "register" | "login" | "bind";

type Entry = {
  code: string;
  expiresAt: number;
  resendAt: number;
  failures: number;
};

// This store exists only in browser memory. A production app must issue and
// verify codes on the server, with provider delivery and server-side limits.
export class DemoVerification {
  private codes = new Map<string, Entry>();
  private readonly now: () => number;

  constructor(now: () => number = Date.now) {
    this.now = now;
  }

  private key(
    purpose: CodePurpose,
    type: ContactType,
    contact: string,
  ): string {
    return `${purpose}:${type}:${contact}`;
  }

  issue(purpose: CodePurpose, type: ContactType, contact: string): string {
    const key = this.key(purpose, type, contact);
    const current = this.codes.get(key);
    if (current && current.resendAt > this.now())
      throw new Error(
        `请 ${Math.ceil((current.resendAt - this.now()) / 1000)} 秒后重发`,
      );
    const value = crypto.getRandomValues(new Uint32Array(1))[0]!;
    const code = String(value % 1_000_000).padStart(6, "0");
    this.codes.set(key, {
      code,
      expiresAt: this.now() + 5 * 60_000,
      resendAt: this.now() + 60_000,
      failures: 0,
    });
    return code;
  }

  remaining(purpose: CodePurpose, type: ContactType, contact: string): number {
    const entry = this.codes.get(this.key(purpose, type, contact));
    return entry
      ? Math.max(0, Math.ceil((entry.resendAt - this.now()) / 1000))
      : 0;
  }

  verify(
    purpose: CodePurpose,
    type: ContactType,
    contact: string,
    input: string,
  ): void {
    const key = this.key(purpose, type, contact);
    const entry = this.codes.get(key);
    if (!entry || this.now() >= entry.expiresAt) {
      this.codes.delete(key);
      throw new Error("验证码不存在或已过期，请重新获取");
    }
    if (entry.failures >= 5)
      throw new Error("验证码错误次数过多，请等待重新获取");
    if (!/^\d{6}$/.test(input.trim()) || input.trim() !== entry.code) {
      entry.failures++;
      throw new Error("验证码不正确");
    }
  }

  consume(purpose: CodePurpose, type: ContactType, contact: string): void {
    this.codes.delete(this.key(purpose, type, contact));
  }
}

export const demoCodes = new DemoVerification();
