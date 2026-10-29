import { ref, watch } from "vue";
import {
  bindContact as bindAccountContact,
  checkRegistration,
  loginWithCode,
  loginWithPassword,
  register,
  resetPassword,
  sendCode,
  type Account,
  type CodePurpose,
} from "./authApi";
import {
  normalizeContact,
  validContact,
  validPassword,
  validateRegistration,
  type ContactType,
  type RegistrationInput,
} from "./authValidation";

type CodeSlot = "phone" | "email" | "login" | "bind";
type Mode = "login" | "register" | "reset";

type Deps = {
  hasAccount: () => boolean;
  /** Contact field the signed-in account still lacks, or null. */
  missingContact: () => ContactType | null;
  /** A successful login or registration hands the account to the shell. */
  signedIn: (account: Account) => void;
  /** Binding a second contact updates the signed-in profile in place. */
  accountUpdated: (account: Account) => void;
};

/** Login, registration, password reset and contact binding, plus their form state. */
export function useAuthForm(deps: Deps) {
  const mode = ref<Mode>("login");
  const step = ref<"details" | "verify">("details");
  const pending = ref<RegistrationInput | null>(null);
  const contactType = ref<ContactType>("phone");
  const loginMethod = ref<"password" | "code">("password");
  const identifier = ref("");
  const phone = ref("");
  const email = ref("");
  const phoneCode = ref("");
  const emailCode = ref("");
  const loginCode = ref("");
  const password = ref("");
  const confirm = ref("");
  const name = ref("");
  const handle = ref("");
  const error = ref("");
  const success = ref("");
  const busy = ref(false);
  const showPassword = ref(false);
  const bindValue = ref("");
  const bindPassword = ref("");
  const bindCode = ref("");
  const bindError = ref("");
  const bindSuccess = ref("");
  const bindBusy = ref(false);
  const hints = ref<Record<CodeSlot, string>>({ phone: "", email: "", login: "", bind: "" });
  const codeBusy = ref<Record<CodeSlot, boolean>>({ phone: false, email: false, login: false, bind: false });
  const resendAt = ref<Record<string, number>>({});
  const tick = ref(Date.now());
  const timer = setInterval(() => (tick.value = Date.now()), 1000);
  // Browsers return a number; Node returns a handle that would keep a test run alive.
  (timer as unknown as { unref?: () => void }).unref?.();

  watch(phone, () => {
    phoneCode.value = "";
    hints.value.phone = "";
  });
  watch(email, () => {
    emailCode.value = "";
    hints.value.email = "";
  });
  watch(identifier, () => {
    loginCode.value = "";
    hints.value.login = "";
  });
  watch(bindValue, () => {
    bindCode.value = "";
    hints.value.bind = "";
  });

  function switchMode(next: Mode) {
    mode.value = next;
    step.value = "details";
    pending.value = null;
    error.value = "";
    success.value = "";
    password.value = "";
    confirm.value = "";
    phoneCode.value = "";
    emailCode.value = "";
    loginCode.value = "";
    hints.value = { phone: "", email: "", login: "", bind: "" };
  }

  function editRegistration() {
    step.value = "details";
    pending.value = null;
    phoneCode.value = "";
    emailCode.value = "";
    hints.value.phone = "";
    hints.value.email = "";
    error.value = "";
  }

  function secondsLeft(purpose: CodePurpose, type: ContactType, raw: string): number {
    void tick.value;
    const key = `${purpose}:${type}:${normalizeContact(type, raw)}`;
    return Math.max(0, Math.ceil(((resendAt.value[key] || 0) - Date.now()) / 1000));
  }

  async function requestCode(
    purpose: CodePurpose,
    type: ContactType,
    raw: string,
    slot: CodeSlot,
  ) {
    if (codeBusy.value[slot]) return;
    error.value = "";
    bindError.value = "";
    codeBusy.value[slot] = true;
    try {
      if (!validContact(type, raw))
        throw new Error(type === "phone" ? "请先输入正确的中国大陆手机号" : "请先输入正确的邮箱地址");
      const contact = normalizeContact(type, raw);
      const response = await sendCode(purpose, type, contact);
      // A code may arrive after the form moved on; only fill the slot it was sent for.
      const stillCurrent = slot === "phone"
        ? mode.value === "register" && step.value === "verify" && pending.value?.phone === contact
        : slot === "email"
          ? mode.value === "register" && step.value === "verify" && pending.value?.email === contact
          : slot === "bind"
            ? deps.missingContact() === type && normalizeContact(type, bindValue.value) === contact
            : mode.value === (purpose === "reset" ? "reset" : "login") &&
              contactType.value === type && normalizeContact(type, identifier.value) === contact;
      if (!stillCurrent) return;
      resendAt.value[`${purpose}:${type}:${contact}`] = Date.now() + response.retryAfterSeconds * 1000;
      if (response.devCode && /^[0-9]{6}$/.test(response.devCode)) {
        if (slot === "phone") phoneCode.value = response.devCode;
        else if (slot === "email") emailCode.value = response.devCode;
        else if (slot === "bind") bindCode.value = response.devCode;
        else loginCode.value = response.devCode;
      }
      hints.value[slot] = response.devCode
        ? `本地开发验证码已自动填入（${Math.ceil(response.expiresInSeconds / 60)} 分钟有效；不会真实发送）`
        : "验证码已发送";
      tick.value = Date.now();
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : "无法获取验证码";
      if (slot === "bind") bindError.value = message;
      else error.value = message;
    } finally {
      codeBusy.value[slot] = false;
    }
  }

  async function submit() {
    if (busy.value) return;
    error.value = "";
    success.value = "";
    busy.value = true;
    try {
      if (mode.value === "register") {
        if (step.value === "details") {
          if (!validPassword(password.value)) throw new Error("密码需为 8–72 位，并包含字母和数字");
          if (password.value !== confirm.value) throw new Error("两次输入的密码不一致");
          const input = {
            name: name.value,
            handle: handle.value,
            phone: phone.value,
            email: email.value,
            password: password.value,
          };
          const checked = validateRegistration(input);
          await checkRegistration({ ...checked, password: input.password });
          pending.value = { ...checked, password: input.password };
          phoneCode.value = "";
          emailCode.value = "";
          step.value = "verify";
          return;
        }
        if (!pending.value) throw new Error("请先填写注册资料");
        const account = await register({
          ...pending.value,
          phoneCode: phoneCode.value,
          emailCode: emailCode.value,
        });
        contactType.value = account.phone ? "phone" : "email";
        identifier.value = account.phone || account.email || "";
        switchMode("login");
        success.value = "注册成功！请用已验证的手机号或邮箱登录。";
        return;
      }
      if (!validContact(contactType.value, identifier.value))
        throw new Error("请输入有效的手机号或邮箱");
      if (mode.value === "reset") {
        if (!validPassword(password.value)) throw new Error("密码需为 8–72 位，并包含字母和数字");
        if (password.value !== confirm.value) throw new Error("两次输入的密码不一致");
        if (!/^[0-9]{6}$/.test(loginCode.value)) throw new Error("请输入 6 位验证码");
        await resetPassword(
          contactType.value,
          normalizeContact(contactType.value, identifier.value),
          loginCode.value,
          password.value,
        );
        switchMode("login");
        success.value = "密码已重设，请用新密码登录。其他设备需要重新登录。";
        return;
      }
      const account = loginMethod.value === "password"
        ? await loginWithPassword(
            contactType.value,
            normalizeContact(contactType.value, identifier.value),
            password.value,
          )
        : await loginWithCode(
            contactType.value,
            normalizeContact(contactType.value, identifier.value),
            loginCode.value,
          );
      deps.signedIn(account);
      password.value = "";
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : "操作失败，请稍后再试";
    } finally {
      busy.value = false;
    }
  }

  async function bind() {
    const type = deps.missingContact();
    if (!deps.hasAccount() || !type || bindBusy.value) return;
    bindError.value = "";
    bindSuccess.value = "";
    bindBusy.value = true;
    try {
      const updated = await bindAccountContact(
        type,
        normalizeContact(type, bindValue.value),
        bindPassword.value,
        bindCode.value,
      );
      deps.accountUpdated(updated);
      bindValue.value = "";
      bindPassword.value = "";
      bindCode.value = "";
      bindSuccess.value = type === "phone" ? "手机号已绑定，可用于登录" : "邮箱已绑定，可用于登录";
    } catch (cause) {
      bindError.value = cause instanceof Error ? cause.message : "绑定失败";
    } finally {
      bindBusy.value = false;
    }
  }

  /** Back to a clean login form; used when the session ends. */
  function reset() {
    bindValue.value = "";
    bindPassword.value = "";
    bindCode.value = "";
    bindError.value = "";
    bindSuccess.value = "";
    switchMode("login");
  }

  function dispose() {
    clearInterval(timer);
  }

  return {
    mode, step, pending, contactType, loginMethod, identifier, phone, email,
    phoneCode, emailCode, loginCode, password, confirm, name, handle,
    error, success, busy, showPassword,
    bindValue, bindPassword, bindCode, bindError, bindSuccess, bindBusy,
    hints, codeBusy, secondsLeft,
    switchMode, editRegistration, requestCode, submit, bind, reset, dispose,
  };
}
