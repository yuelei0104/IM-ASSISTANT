<script setup lang="ts">
import AppIcon from "./AppIcon.vue";
import type { useAuthForm } from "../useAuthForm";

const props = defineProps<{
  /** Restoring a stored session: show the splash instead of the form to avoid a login flash. */
  restoring: boolean;
  auth: ReturnType<typeof useAuthForm>;
}>();

const {
  mode: authMode, step: registerStep, pending: pendingRegistration,
  contactType, loginMethod, identifier: authIdentifier,
  phone: authPhone, email: authEmail,
  phoneCode: authPhoneCode, emailCode: authEmailCode, loginCode: authLoginCode,
  password: authPassword, confirm: authConfirm,
  name: authName, handle: authHandle,
  error: authError, success: authSuccess, busy: authBusy, showPassword,
  hints: codeHints, codeBusy, secondsLeft: waitSeconds,
  switchMode: switchAuthMode, editRegistration, requestCode, submit: submitAuth,
} = props.auth;
</script>

<template>
  <div v-if="restoring" class="auth-loading" role="status">正在恢复登录状态…</div>
  <div v-else class="auth-shell">
    <div class="auth-visual">
      <div class="brand light">
        <span class="brand-mark"><AppIcon name="sparkles" /></span> IM 助手
      </div>
      <div class="auth-art">
        <div class="art-card"><span class="avatar peach tiny">夏</span>我们周五一起确认方案吧。</div>
        <div class="art-card second"><span class="avatar blue tiny">诺</span>好，我把下一步记下来。</div>
        <div class="art-glow">✦</div>
      </div>
      <div class="auth-copy">
        <p class="overline">CONNECT · COLLABORATE · CREATE</p>
        <h1>聊得清楚，<br />事情也更有着落。</h1>
        <p>把重要对话、好友协作与下一步计划，放在同一个舒适的空间。</p>
      </div>
      <small>IM ASSISTANT · FRONTEND PREVIEW</small>
    </div>
    <div class="auth-form-wrap">
      <form class="auth-card" @submit.prevent="submitAuth">
        <span class="eyebrow-chip">账号已接入用户服务</span>
        <h2>
          {{
            authMode === "login"
              ? "欢迎回来"
              : authMode === "reset"
                ? "重设密码"
                : registerStep === "verify"
                  ? "验证联系方式"
                  : "创建你的账号"
          }}
        </h2>
        <p>
          {{
            authMode === "login"
              ? "用已绑定的手机号或邮箱登录。"
              : authMode === "reset"
                ? "验证已绑定的手机号或邮箱，然后设置新密码。"
                : registerStep === "verify"
                  ? "确认验证码后，才会创建你的账号。"
                  : "先填写注册资料，下一步验证联系方式。"
          }}
        </p>
        <div
          v-if="authMode === 'login' || authMode === 'reset'"
          class="auth-tabs"
          role="group"
          aria-label="联系方式"
        >
          <button
            type="button"
            :class="{ active: contactType === 'phone' }"
            :aria-pressed="contactType === 'phone'"
            @click="
              contactType = 'phone';
              authIdentifier = '';
              authError = '';
            "
          >
            手机号
          </button>
          <button
            type="button"
            :class="{ active: contactType === 'email' }"
            :aria-pressed="contactType === 'email'"
            @click="
              contactType = 'email';
              authIdentifier = '';
              authError = '';
            "
          >
            邮箱
          </button>
        </div>
        <template v-if="authMode === 'register' && registerStep === 'details'">
          <label for="auth-name">昵称</label>
          <input
            id="auth-name"
            v-model="authName"
            autocomplete="nickname"
            maxlength="24"
            placeholder="例如：岳蕾"
            required
          />
          <label for="auth-handle">唯一账号（用于添加好友）</label>
          <div class="input-prefix">
            <span>@</span>
            <input
              id="auth-handle"
              v-model="authHandle"
              autocomplete="username"
              maxlength="20"
              placeholder="your_handle"
              required
            />
          </div>
          <p class="auth-contact-hint">手机号和邮箱至少填一项；都填将关联同一个账号。</p>
          <label for="auth-phone">手机号 <span>可选</span></label>
          <input
            id="auth-phone"
            v-model="authPhone"
            type="tel"
            inputmode="tel"
            autocomplete="tel"
            maxlength="20"
            placeholder="请输入 11 位手机号"
          />
          <label for="auth-email">邮箱地址 <span>可选</span></label>
          <input
            id="auth-email"
            v-model="authEmail"
            type="email"
            inputmode="email"
            autocomplete="email"
            maxlength="254"
            placeholder="name@example.com"
          />
        </template>
        <template
          v-if="authMode === 'register' && registerStep === 'verify' && pendingRegistration"
        >
          <p class="auth-contact-hint">
            资料已通过服务端检查。请验证以下每一项联系方式；如果修改联系方式，请重新获取验证码。
          </p>
          <template v-if="pendingRegistration.phone">
            <label for="auth-phone-code">手机号 · {{ pendingRegistration.phone }}</label>
            <div class="verification-field">
              <input
                id="auth-phone-code"
                v-model="authPhoneCode"
                inputmode="numeric"
                autocomplete="one-time-code"
                maxlength="6"
                placeholder="输入手机验证码"
                required
              />
              <button
                type="button"
                :disabled="
                  codeBusy.phone || waitSeconds('register', 'phone', pendingRegistration.phone) > 0
                "
                @click="requestCode('register', 'phone', pendingRegistration.phone!, 'phone')"
              >
                {{
                  waitSeconds("register", "phone", pendingRegistration.phone)
                    ? `${waitSeconds("register", "phone", pendingRegistration.phone)} 秒`
                    : "获取验证码"
                }}
              </button>
            </div>
            <p v-if="codeHints.phone" class="demo-code" role="status">{{ codeHints.phone }}</p>
          </template>
          <template v-if="pendingRegistration.email">
            <label for="auth-email-code">邮箱 · {{ pendingRegistration.email }}</label>
            <div class="verification-field">
              <input
                id="auth-email-code"
                v-model="authEmailCode"
                inputmode="numeric"
                autocomplete="one-time-code"
                maxlength="6"
                placeholder="输入邮箱验证码"
                required
              />
              <button
                type="button"
                :disabled="
                  codeBusy.email || waitSeconds('register', 'email', pendingRegistration.email) > 0
                "
                @click="requestCode('register', 'email', pendingRegistration.email!, 'email')"
              >
                {{
                  waitSeconds("register", "email", pendingRegistration.email)
                    ? `${waitSeconds("register", "email", pendingRegistration.email)} 秒`
                    : "获取验证码"
                }}
              </button>
            </div>
            <p v-if="codeHints.email" class="demo-code" role="status">{{ codeHints.email }}</p>
          </template>
        </template>
        <label v-if="authMode !== 'register'" for="auth-identifier">{{
          contactType === "phone" ? "手机号" : "邮箱地址"
        }}</label>
        <input
          v-if="authMode !== 'register'"
          id="auth-identifier"
          v-model="authIdentifier"
          :type="contactType === 'phone' ? 'tel' : 'email'"
          :inputmode="contactType === 'phone' ? 'tel' : 'email'"
          :autocomplete="contactType === 'phone' ? 'tel' : 'email'"
          :placeholder="contactType === 'phone' ? '请输入 11 位手机号' : 'name@example.com'"
          :maxlength="contactType === 'phone' ? 20 : 254"
          required
        />
        <div v-if="authMode === 'login'" class="login-method">
          <button
            type="button"
            :class="{ active: loginMethod === 'password' }"
            @click="
              loginMethod = 'password';
              authError = '';
            "
          >
            密码登录
          </button>
          <button
            type="button"
            :class="{ active: loginMethod === 'code' }"
            @click="
              loginMethod = 'code';
              authError = '';
            "
          >
            验证码登录
          </button>
        </div>
        <template v-if="authMode === 'reset' || (authMode === 'login' && loginMethod === 'code')">
          <label for="auth-login-code">{{
            contactType === "phone" ? "手机验证码" : "邮箱验证码"
          }}</label>
          <div class="verification-field">
            <input
              id="auth-login-code"
              v-model="authLoginCode"
              inputmode="numeric"
              autocomplete="one-time-code"
              maxlength="6"
              placeholder="输入 6 位验证码"
              required
            />
            <button
              type="button"
              :disabled="
                codeBusy.login ||
                waitSeconds(authMode === 'reset' ? 'reset' : 'login', contactType, authIdentifier) >
                  0
              "
              @click="
                requestCode(
                  authMode === 'reset' ? 'reset' : 'login',
                  contactType,
                  authIdentifier,
                  'login',
                )
              "
            >
              {{
                waitSeconds(authMode === "reset" ? "reset" : "login", contactType, authIdentifier)
                  ? `${waitSeconds(authMode === "reset" ? "reset" : "login", contactType, authIdentifier)} 秒`
                  : "获取验证码"
              }}
            </button>
          </div>
          <p v-if="codeHints.login" class="demo-code" role="status">{{ codeHints.login }}</p>
        </template>
        <label
          v-if="
            authMode === 'reset' ||
            (authMode === 'register' && registerStep === 'details') ||
            (authMode === 'login' && loginMethod === 'password')
          "
          for="auth-password"
          >{{ authMode === "reset" ? "新密码" : "密码" }}</label
        >
        <div
          v-if="
            authMode === 'reset' ||
            (authMode === 'register' && registerStep === 'details') ||
            (authMode === 'login' && loginMethod === 'password')
          "
          class="password-field"
        >
          <input
            id="auth-password"
            v-model="authPassword"
            :type="showPassword ? 'text' : 'password'"
            :autocomplete="authMode !== 'login' ? 'new-password' : 'current-password'"
            :placeholder="authMode !== 'login' ? '至少 8 位，包含字母和数字' : '请输入密码'"
            :minlength="authMode !== 'login' ? 8 : undefined"
            required
          />
          <button
            type="button"
            :aria-label="showPassword ? '隐藏密码' : '显示密码'"
            @click="showPassword = !showPassword"
          >
            <AppIcon :name="showPassword ? 'eyeoff' : 'eye'" :size="17" />
          </button>
        </div>
        <template
          v-if="authMode === 'reset' || (authMode === 'register' && registerStep === 'details')"
        >
          <label for="auth-confirm">确认密码</label>
          <input
            id="auth-confirm"
            v-model="authConfirm"
            :type="showPassword ? 'text' : 'password'"
            autocomplete="new-password"
            placeholder="再次输入密码"
            required
          />
        </template>
        <p v-if="authSuccess" class="success-text" role="status">{{ authSuccess }}</p>
        <p v-if="authError" class="error-text" role="alert">{{ authError }}</p>
        <button class="primary-button full" type="submit" :disabled="authBusy">
          {{
            authBusy
              ? "处理中…"
              : authMode === "register"
                ? registerStep === "details"
                  ? "下一步 · 验证联系方式"
                  : "验证并创建账号"
                : authMode === "reset"
                  ? "验证并重设密码"
                  : loginMethod === "code"
                    ? "验证码登录"
                    : "密码登录"
          }}
          <AppIcon name="arrowRight" :size="17" />
        </button>
        <button
          v-if="authMode === 'login' && loginMethod === 'password'"
          class="auth-back"
          type="button"
          @click="switchAuthMode('reset')"
        >
          忘记密码？
        </button>
        <button
          v-if="authMode === 'reset'"
          class="auth-back"
          type="button"
          @click="switchAuthMode('login')"
        >
          返回登录
        </button>
        <button
          v-if="authMode === 'register' && registerStep === 'verify'"
          class="auth-back"
          type="button"
          @click="editRegistration"
        >
          返回修改资料
        </button>
        <button
          v-if="authMode !== 'reset'"
          class="auth-switch"
          type="button"
          @click="switchAuthMode(authMode === 'login' ? 'register' : 'login')"
        >
          {{ authMode === "login" ? "还没有账号？先注册" : "已经有账号？去登录" }}
        </button>
        <p class="auth-disclaimer">
          本地测试模式启用后会自动填入验证码，不发送短信或邮件。正式使用需配置实际验证服务。
        </p>
      </form>
    </div>
  </div>
</template>
