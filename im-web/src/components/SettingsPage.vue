<script setup lang="ts">
import type { Account } from "../authApi";
import type { ContactType } from "../authValidation";
import type { DemoState } from "../demo";
import type { useAuthForm } from "../useAuthForm";
import type { useNotices } from "../useNotices";

const props = defineProps<{
  /** Passed already unwrapped by the shell's template; the object itself is reactive. */
  data: DemoState;
  account: Account | null;
  missingContact: ContactType | null;
  auth: ReturnType<typeof useAuthForm>;
  notices: ReturnType<typeof useNotices>;
}>();

const emit = defineEmits<{
  signOut: [];
  resetDemo: [];
}>();

const {
  bindValue, bindPassword, bindCode, bindError, bindSuccess, bindBusy,
  hints: codeHints, codeBusy, secondsLeft: waitSeconds, requestCode, bind: bindContact,
} = props.auth;
const {
  keywordBusy, keywordError, keywordSaved, saveFocusWords,
} = props.notices;
</script>

<template>
  <div class="page-content settings-content">
    <div class="content-card">
      <div class="card-heading">
        <h2>个人资料</h2>
        <span>服务端账号资料</span>
      </div>
      <div class="profile-setting">
        <span class="avatar violet large">{{ data.displayName[0] }}</span>
        <div>
          <strong>{{ data.displayName }}</strong
          ><span>@{{ data.handle }}</span>
        </div>
        <button class="outline-button" @click="emit('signOut')">退出登录</button>
      </div>
      <div class="account-contacts">
        <div>
          <span>手机号</span><strong>{{ account?.phone || "未绑定" }}</strong>
        </div>
        <div>
          <span>邮箱</span><strong>{{ account?.email || "未绑定" }}</strong>
        </div>
      </div>
      <form v-if="missingContact" class="bind-form" @submit.prevent="bindContact">
        <strong>补绑{{ missingContact === "phone" ? "手机号" : "邮箱" }}</strong>
        <p>确认当前密码后，将另一种联系方式绑定到你的账号。</p>
        <label for="bind-value">{{ missingContact === "phone" ? "手机号" : "邮箱地址" }}</label>
        <input
          id="bind-value"
          v-model="bindValue"
          :type="missingContact === 'phone' ? 'tel' : 'email'"
          :autocomplete="missingContact === 'phone' ? 'tel' : 'email'"
          :placeholder="missingContact === 'phone' ? '请输入 11 位手机号' : 'name@example.com'"
          required
        />
        <label for="bind-code">{{ missingContact === "phone" ? "手机验证码" : "邮箱验证码" }}</label>
        <div class="verification-field">
          <input
            id="bind-code"
            v-model="bindCode"
            inputmode="numeric"
            maxlength="6"
            placeholder="输入 6 位验证码"
            required
          />
          <button
            type="button"
            :disabled="codeBusy.bind || waitSeconds('bind', missingContact, bindValue) > 0"
            @click="requestCode('bind', missingContact, bindValue, 'bind')"
          >
            {{
              waitSeconds("bind", missingContact, bindValue)
                ? `${waitSeconds("bind", missingContact, bindValue)} 秒`
                : "获取验证码"
            }}
          </button>
        </div>
        <p v-if="codeHints.bind" class="demo-code" role="status">{{ codeHints.bind }}</p>
        <label for="bind-password">当前密码</label>
        <input
          id="bind-password"
          v-model="bindPassword"
          type="password"
          autocomplete="current-password"
          placeholder="输入当前账号密码"
          required
        />
        <p v-if="bindError" class="error-text" role="alert">{{ bindError }}</p>
        <button class="primary-button" type="submit" :disabled="bindBusy">
          {{ bindBusy ? "处理中…" : "关联到当前账号" }}
        </button>
        <small>本地测试模式会自动填入验证码；正式环境邮箱验证需配置 SMTP，短信暂未接入。</small>
      </form>
      <p v-if="bindSuccess" class="success-text" role="status">{{ bindSuccess }}</p>
    </div>
    <div class="content-card">
      <div class="card-heading">
        <h2>阅读与提醒</h2>
        <span>按你的节奏来</span>
      </div>
      <div class="setting-row">
        <div>
          <strong>消息字号</strong>
          <p>让聊天文字更容易阅读</p>
        </div>
        <div class="segmented setting-segment">
          <button
            v-for="size in [16, 18, 20] as const"
            :key="size"
            :class="{ active: data.preferences.fontSize === size }"
            @click="data.preferences.fontSize = size"
          >
            {{ size }}px
          </button>
        </div>
      </div>
      <div class="setting-row">
        <div>
          <strong>关注词</strong>
          <p>最多 5 个，每个 2–32 字；群消息和字幕命中后会提醒你</p>
        </div>
        <input
          v-model="data.preferences.focusWords"
          class="setting-input"
          aria-label="关注词"
          :disabled="keywordBusy"
          @input="keywordSaved = false"
        />
        <button class="soft-button" type="button" :disabled="keywordBusy" @click="saveFocusWords">
          {{ keywordBusy ? "处理中…" : "保存关注词" }}
        </button>
      </div>
      <p v-if="keywordError" class="error-text" role="alert">{{ keywordError }}</p>
      <p v-if="keywordSaved" class="success-text" role="status">关注词已保存</p>
      <div class="setting-row">
        <div>
          <strong>免打扰时段</strong>
          <p>偏好已保存，推送能力待接入</p>
        </div>
        <label class="switch"
          ><input v-model="data.preferences.quietHours" type="checkbox" aria-label="免打扰时段" /><span
        /></label>
      </div>
    </div>
    <div class="content-card">
      <div class="card-heading"><h2>语言与显示</h2></div>
      <div class="setting-row">
        <div>
          <strong>默认翻译目标</strong>
          <p>群消息点击“翻译”时使用，译文需检查后手动发送</p>
        </div>
        <select v-model="data.preferences.translationTarget" aria-label="默认翻译目标">
          <option value="zh">简体中文</option>
          <option value="en">English</option>
        </select>
      </div>
      <div class="setting-row">
        <div>
          <strong>显示译文</strong>
          <p>原文始终保留，译文分开展示</p>
        </div>
        <label class="switch"
          ><input v-model="data.preferences.showTranslations" type="checkbox" aria-label="显示译文" /><span
        /></label>
      </div>
      <div class="setting-row">
        <div>
          <strong>时区</strong>
          <p>用于未来的提醒与待办时间</p>
        </div>
        <select v-model="data.preferences.timezone" aria-label="时区">
          <option value="Asia/Shanghai">Asia/Shanghai</option>
          <option value="UTC">UTC</option>
        </select>
      </div>
    </div>
    <div class="content-card danger-card">
      <div>
        <strong>演示数据</strong>
        <p>待办和聊天记录保存在服务端；界面偏好及部分演示内容保存在本地。</p>
      </div>
      <button class="outline-button" @click="emit('resetDemo')">重置演示数据</button>
    </div>
  </div>
</template>
