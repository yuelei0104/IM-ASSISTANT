<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import AppIcon from "./components/AppIcon.vue";
import {
  accountStateKey,
  accounts,
  authenticateAccount,
  bindAccountContact,
  registerAccount,
  sessionAccount,
  sessionKey,
  validContact,
  validPassword,
  type ContactType,
  type DemoAccount,
} from "./demoAuth";
import {
  clock,
  initialState,
  selfId,
  uid,
  type Conversation,
  type DemoState,
  type Person,
} from "./demo";

type Page = "chat" | "contacts" | "requests" | "notices" | "tasks" | "settings";
type Modal = "group" | "invite" | "friend" | null;
const key = "im-assistant-demo-v1";
function load(account: DemoAccount | null): DemoState {
  if (!account) return initialState();
  try {
    const saved = localStorage.getItem(accountStateKey(account.id));
    if (saved) {
      const v = JSON.parse(saved);
      if (v.people && v.conversations && v.preferences) return v;
    }
  } catch {}
  return initialState();
}
const currentAccount = ref<DemoAccount | null>(sessionAccount());
const data = ref<DemoState>(load(currentAccount.value));
const loggedIn = computed(() => currentAccount.value !== null);
const authMode = ref<"login" | "register">("login");
const contactType = ref<ContactType>("phone");
const authIdentifier = ref("");
const authPhone = ref("");
const authEmail = ref("");
const authPassword = ref("");
const authConfirm = ref("");
const authName = ref("");
const authHandle = ref("");
const authError = ref("");
const authSuccess = ref("");
const authBusy = ref(false);
const showPassword = ref(false);
const bindValue = ref("");
const bindPassword = ref("");
const bindError = ref("");
const bindSuccess = ref("");
const bindBusy = ref(false);
const missingContact = computed<ContactType | null>(() =>
  !currentAccount.value?.phone
    ? "phone"
    : !currentAccount.value?.email
      ? "email"
      : null,
);
const page = ref<Page>("chat");
const activeId = ref("product");
const search = ref("");
const composer = ref("");
const modal = ref<Modal>(null);
const infoOpen = ref(false);
const assistantOpen = ref(false);
const mobileChatOpen = ref(false);
const unreadMarker = ref("");
const toast = ref("");
const range = ref<"recent" | "all">("recent");
const showSummary = ref(false);
const taskDraft = ref("");
const groupName = ref("");
const inviteIds = ref<string[]>([]);
const friendHandle = ref("");
const friendNote = ref("");
const showOlder = ref(false);
const focusId = ref("");
const messageScroll = ref<HTMLElement | null>(null);
const voiceActive = ref(false);
const interim = ref("");
let recognition: any = null;
let toastTimer: ReturnType<typeof setTimeout> | undefined;
const nav: { id: Page; label: string; icon: string }[] = [
  { id: "chat", label: "消息", icon: "message" },
  { id: "contacts", label: "联系人", icon: "users" },
  { id: "requests", label: "好友申请", icon: "userPlus" },
  { id: "notices", label: "通知", icon: "bell" },
  { id: "tasks", label: "我的待办", icon: "checkSquare" },
  { id: "settings", label: "设置", icon: "settings" },
];
watch(
  data,
  (v) => {
    try {
      if (currentAccount.value)
        localStorage.setItem(
          accountStateKey(currentAccount.value.id),
          JSON.stringify(v),
        );
    } catch {}
  },
  { deep: true },
);
watch(activeId, () => {
  showSummary.value = false;
  showOlder.value = false;
  stopVoice();
  nextTick(scrollBottom);
});
watch(page, () => {
  stopVoice();
  infoOpen.value = false;
  if (page.value !== "chat") mobileChatOpen.value = false;
});
const active = computed(() =>
  data.value.conversations.find((c) => c.id === activeId.value),
);
const messages = computed(() =>
  data.value.messages.filter((m) => m.conversationId === activeId.value),
);
const visibleMessages = computed(() =>
  showOlder.value ? messages.value : messages.value.slice(-12),
);
const conversations = computed(() =>
  data.value.conversations.filter(
    (c) =>
      c.name.toLowerCase().includes(search.value.toLowerCase()) ||
      data.value.messages.some(
        (m) =>
          m.conversationId === c.id &&
          m.text.toLowerCase().includes(search.value.toLowerCase()),
      ),
  ),
);
const friends = computed(() =>
  data.value.people.filter((p) => data.value.friendIds.includes(p.id)),
);
const received = computed(() =>
  data.value.requests.filter((r) => r.toId === selfId),
);
const sent = computed(() =>
  data.value.requests.filter((r) => r.fromId === selfId),
);
const unread = computed(() => data.value.notices.filter((n) => !n.read).length);
const openTasks = computed(
  () => data.value.todos.filter((t) => !t.done).length,
);
const canWrite = computed(
  () =>
    !!active.value &&
    (active.value.kind === "group"
      ? active.value.memberIds.includes(selfId)
      : active.value.memberIds.every(
          (id) => id === selfId || data.value.friendIds.includes(id),
        )),
);
const candidates = computed(() =>
  friends.value.filter((p) => !active.value?.memberIds.includes(p.id)),
);
const focusCount = computed(
  () =>
    messages.value.filter(
      (m) =>
        m.senderId !== selfId &&
        data.value.preferences.focusWords
          .split(/[,，]/)
          .some((w) => w.trim() && m.text.includes(w.trim())),
    ).length,
);
const summarySources = computed(() =>
  (range.value === "recent" ? messages.value.slice(-5) : messages.value)
    .filter((m) => m.kind !== "system" && m.senderId !== selfId)
    .slice(-3),
);
function person(id: string): Person {
  return data.value.people.find((p) => p.id === id) || data.value.people[0]!;
}
function last(c: Conversation) {
  return data.value.messages.filter((m) => m.conversationId === c.id).at(-1);
}
function notify(s: string) {
  toast.value = s;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (toast.value = ""), 3500);
}
function scrollBottom() {
  if (messageScroll.value)
    messageScroll.value.scrollTop = messageScroll.value.scrollHeight;
}
function openChat(id: string, source?: string) {
  const previous = data.value.conversations.find((x) => x.id === id);
  const unreadMessages = data.value.messages.filter(
    (m) => m.conversationId === id,
  );
  unreadMarker.value = previous?.unread
    ? unreadMessages.at(-previous.unread)?.id || ""
    : "";
  mobileChatOpen.value = true;
  activeId.value = id;
  page.value = "chat";
  assistantOpen.value = false;
  const c = data.value.conversations.find((x) => x.id === id);
  if (c) c.unread = 0;
  if (source) {
    showOlder.value = true;
    focusId.value = source;
    nextTick(() =>
      document
        .getElementById(`msg-${source}`)
        ?.scrollIntoView({ behavior: "smooth", block: "center" }),
    );
    setTimeout(() => (focusId.value = ""), 3000);
  } else nextTick(scrollBottom);
}
function switchAuthMode(next: "login" | "register") {
  authMode.value = next;
  authError.value = "";
  authSuccess.value = "";
  authPassword.value = "";
  authConfirm.value = "";
}
async function submitAuth() {
  if (authBusy.value) return;
  authError.value = "";
  authSuccess.value = "";
  authBusy.value = true;
  try {
    if (authMode.value === "register") {
      if (!validPassword(authPassword.value))
        throw new Error("密码需为 8–72 位，并包含字母和数字");
      if (authPassword.value !== authConfirm.value)
        throw new Error("两次输入的密码不一致");
      const hadAccounts = accounts().length > 0;
      const account = await registerAccount({
        name: authName.value,
        handle: authHandle.value,
        phone: authPhone.value,
        email: authEmail.value,
        password: authPassword.value,
      });
      if (!hadAccounts) {
        try {
          const legacy = localStorage.getItem(key);
          if (legacy) localStorage.setItem(accountStateKey(account.id), legacy);
        } catch {}
      }
      contactType.value = account.phone ? "phone" : "email";
      authIdentifier.value = account.phone || account.email || "";
      switchAuthMode("login");
      authSuccess.value = "注册成功！请用手机号或邮箱及密码登录。";
      return;
    }
    if (
      !validContact(contactType.value, authIdentifier.value) ||
      !authPassword.value
    )
      throw new Error("请输入有效的手机号或邮箱及密码");
    const account = await authenticateAccount(
      contactType.value,
      authIdentifier.value,
      authPassword.value,
    );
    if (!account) throw new Error("账号或密码不正确，请检查后重试");
    data.value = load(account);
    data.value.displayName = account.name;
    data.value.handle = account.handle;
    data.value.people[0]!.name = account.name;
    data.value.people[0]!.handle = account.handle;
    data.value.people[0]!.initials = account.name[0]!;
    sessionStorage.setItem(sessionKey, account.id);
    currentAccount.value = account;
    authPassword.value = "";
    nextTick(scrollBottom);
  } catch (error) {
    authError.value =
      error instanceof Error ? error.message : "操作失败，请稍后再试";
  } finally {
    authBusy.value = false;
  }
}
function signOut() {
  stopVoice();
  sessionStorage.removeItem(sessionKey);
  currentAccount.value = null;
  authPassword.value = "";
  bindPassword.value = "";
  bindValue.value = "";
  bindError.value = "";
  bindSuccess.value = "";
  switchAuthMode("login");
}
async function bindContact() {
  if (!currentAccount.value || !missingContact.value || bindBusy.value) return;
  bindError.value = "";
  bindSuccess.value = "";
  bindBusy.value = true;
  try {
    const type = missingContact.value;
    const updated = await bindAccountContact(
      currentAccount.value.id,
      type,
      bindValue.value,
      bindPassword.value,
    );
    currentAccount.value = updated;
    bindValue.value = "";
    bindPassword.value = "";
    bindSuccess.value =
      type === "phone"
        ? "手机号已关联到此演示账号，可用于登录"
        : "邮箱已关联到此演示账号，可用于登录";
  } catch (error) {
    bindError.value = error instanceof Error ? error.message : "绑定失败";
  } finally {
    bindBusy.value = false;
  }
}
function send() {
  const text = composer.value.trim();
  if (!text || !active.value || !canWrite.value) return;
  data.value.messages.push({
    id: uid("m"),
    conversationId: activeId.value,
    senderId: selfId,
    text,
    time: clock(),
    kind: "text",
    status: "local",
  });
  composer.value = "";
  nextTick(scrollBottom);
  notify("已保存在本地；接入消息接口后才能发送给其他人");
}
function startDirect(p: Person) {
  if (!data.value.friendIds.includes(p.id)) return;
  let c = data.value.conversations.find(
    (c) => c.kind === "direct" && c.memberIds.includes(p.id),
  );
  if (!c) {
    c = {
      id: uid("dm"),
      name: p.name,
      kind: "direct",
      memberIds: [selfId, p.id],
      unread: 0,
      color: p.color,
      initials: p.initials,
    };
    data.value.conversations.unshift(c);
  }
  openChat(c.id);
}
function requestFriend() {
  const handle = friendHandle.value.trim().replace(/^@/, "").toLowerCase();
  const p = data.value.people.find(
    (p) => p.id !== selfId && p.handle.toLowerCase() === handle,
  );
  if (!p) {
    notify("未找到该账号；演示可搜索 @heyutong");
    return;
  }
  if (data.value.friendIds.includes(p.id)) {
    notify("你们已经是好友");
    return;
  }
  if (
    data.value.requests.some(
      (r) =>
        r.state === "pending" &&
        ((r.fromId === selfId && r.toId === p.id) ||
          (r.toId === selfId && r.fromId === p.id)),
    )
  ) {
    notify("已有待处理的申请");
    return;
  }
  data.value.requests.unshift({
    id: uid("r"),
    fromId: selfId,
    toId: p.id,
    note: friendNote.value.trim() || "你好，想加你为好友。",
    time: clock(),
    state: "pending",
  });
  modal.value = null;
  page.value = "requests";
  notify("申请已保存在本地演示");
}
function resolveRequest(
  id: string,
  action: "accepted" | "rejected" | "withdrawn",
) {
  const r = data.value.requests.find((r) => r.id === id);
  if (!r || r.state !== "pending") return;
  r.state = action;
  if (action === "accepted" && !data.value.friendIds.includes(r.fromId))
    data.value.friendIds.push(r.fromId);
  notify("申请状态已更新");
}
function removeFriend(p: Person) {
  if (!confirm(`移除 ${p.name}？过往私聊仍可查看。`)) return;
  data.value.friendIds = data.value.friendIds.filter((id) => id !== p.id);
  notify("已移除好友；私聊历史仍可查看");
}
function openModal(m: Modal) {
  modal.value = m;
  inviteIds.value = [];
  friendHandle.value = "";
  friendNote.value = "";
  groupName.value = "";
}
function toggleInvite(id: string) {
  inviteIds.value = inviteIds.value.includes(id)
    ? inviteIds.value.filter((x) => x !== id)
    : [...inviteIds.value, id];
}
function createGroup() {
  const name = groupName.value.trim();
  if (!name || inviteIds.value.length > 20) return;
  const c: Conversation = {
    id: uid("group"),
    name,
    kind: "group",
    memberIds: [selfId, ...inviteIds.value],
    ownerId: selfId,
    description: "把讨论变成可以落实的下一步。",
    unread: 0,
    color: "teal",
    initials: name[0]!,
  };
  data.value.conversations.unshift(c);
  data.value.messages.push({
    id: uid("s"),
    conversationId: c.id,
    senderId: selfId,
    text: "讨论室已创建。新成员只看到加入后的消息。",
    time: clock(),
    kind: "system",
  });
  modal.value = null;
  openChat(c.id);
}
function addMembers() {
  if (
    !active.value ||
    active.value.ownerId !== selfId ||
    inviteIds.value.length > 20 ||
    active.value.memberIds.length + inviteIds.value.length > 50
  ) {
    notify("最多 50 人，每次邀请不超过 20 人");
    return;
  }
  active.value.memberIds.push(...inviteIds.value);
  data.value.messages.push({
    id: uid("s"),
    conversationId: activeId.value,
    senderId: selfId,
    text: `${inviteIds.value.map((id) => person(id).name).join("、")}加入了讨论室。`,
    time: clock(),
    kind: "system",
  });
  modal.value = null;
  nextTick(scrollBottom);
}
function openNotice(id: string) {
  const n = data.value.notices.find((n) => n.id === id);
  if (!n) return;
  n.read = true;
  if (n.sourceConversationId) openChat(n.sourceConversationId, n.sourceId);
}
function addTask() {
  const title = taskDraft.value.trim();
  if (!title) return;
  data.value.todos.unshift({
    id: uid("t"),
    title,
    due: "待安排",
    done: false,
    sourceConversationId: page.value === "chat" ? activeId.value : undefined,
    sourceId: page.value === "chat" ? messages.value.at(-1)?.id : undefined,
  });
  taskDraft.value = "";
  page.value = "tasks";
  notify("待办已创建");
}
function reset() {
  if (!confirm("重置所有本地演示数据？")) return;
  data.value = initialState();
  activeId.value = "product";
  notify("演示数据已重置");
}
function stopVoice() {
  if (recognition) {
    recognition.onend = null;
    recognition.stop();
    recognition = null;
  }
  voiceActive.value = false;
  interim.value = "";
}
function toggleVoice() {
  if (voiceActive.value) {
    stopVoice();
    return;
  }
  const Ctor =
    (window as any).SpeechRecognition ||
    (window as any).webkitSpeechRecognition;
  if (!Ctor) {
    notify("此浏览器不支持语音转写");
    return;
  }
  const r = new Ctor();
  r.lang = "zh-CN";
  r.continuous = true;
  r.interimResults = true;
  r.onresult = (event: any) => {
    let partial = "";
    for (let i = event.resultIndex; i < event.results.length; i++) {
      const text = event.results[i][0].transcript;
      if (event.results[i].isFinal)
        composer.value += `${composer.value ? " " : ""}${text}`;
      else partial += text;
    }
    interim.value = partial;
  };
  r.onerror = (event: any) => {
    notify(
      event.error === "not-allowed" ? "麦克风权限未开启" : "语音转写已停止",
    );
    stopVoice();
  };
  r.onend = () => {
    recognition = null;
    voiceActive.value = false;
    interim.value = "";
  };
  try {
    r.start();
    recognition = r;
    voiceActive.value = true;
  } catch {
    notify("无法启动语音转写");
  }
}
function visibility() {
  if (document.hidden) stopVoice();
}
document.addEventListener("visibilitychange", visibility);
onBeforeUnmount(() => {
  stopVoice();
  clearTimeout(toastTimer);
  document.removeEventListener("visibilitychange", visibility);
});
</script>

<template>
  <div v-if="!loggedIn" class="auth-shell">
    <div class="auth-visual">
      <div class="brand light">
        <span class="brand-mark"><AppIcon name="sparkles" /></span> IM 助手
      </div>
      <div class="auth-art">
        <div class="art-card">
          <span class="avatar peach tiny">夏</span>我们周五一起确认方案吧。
        </div>
        <div class="art-card second">
          <span class="avatar blue tiny">诺</span>好，我把下一步记下来。
        </div>
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
        <span class="eyebrow-chip">本地交互演示</span>
        <h2>{{ authMode === "login" ? "欢迎回来" : "创建你的账号" }}</h2>
        <p>
          {{
            authMode === "login"
              ? "用注册时的手机号或邮箱登录。"
              : "先注册账号，再登录你的工作空间。"
          }}
        </p>
        <div
          v-if="authMode === 'login'"
          class="auth-tabs"
          role="group"
          aria-label="登录方式"
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
        <template v-if="authMode === 'register'">
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
          <p class="auth-contact-hint">
            手机号和邮箱至少填一项；都填将关联同一个账号。
          </p>
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
        <label v-if="authMode === 'login'" for="auth-identifier">{{
          contactType === "phone" ? "手机号" : "邮箱地址"
        }}</label>
        <input
          v-if="authMode === 'login'"
          id="auth-identifier"
          v-model="authIdentifier"
          :type="contactType === 'phone' ? 'tel' : 'email'"
          :inputmode="contactType === 'phone' ? 'tel' : 'email'"
          :autocomplete="contactType === 'phone' ? 'tel' : 'email'"
          :placeholder="
            contactType === 'phone' ? '请输入 11 位手机号' : 'name@example.com'
          "
          :maxlength="contactType === 'phone' ? 20 : 254"
          required
        />
        <label for="auth-password">密码</label>
        <div class="password-field">
          <input
            id="auth-password"
            v-model="authPassword"
            :type="showPassword ? 'text' : 'password'"
            :autocomplete="
              authMode === 'register' ? 'new-password' : 'current-password'
            "
            :placeholder="
              authMode === 'register'
                ? '至少 8 位，包含字母和数字'
                : '请输入密码'
            "
            :minlength="authMode === 'register' ? 8 : undefined"
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
        <template v-if="authMode === 'register'">
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
        <p v-if="authSuccess" class="success-text" role="status">
          {{ authSuccess }}
        </p>
        <p v-if="authError" class="error-text" role="alert">{{ authError }}</p>
        <button class="primary-button full" type="submit" :disabled="authBusy">
          {{
            authBusy ? "处理中…" : authMode === "login" ? "登录" : "注册账号"
          }}
          <AppIcon name="arrowRight" :size="17" />
        </button>
        <button
          class="auth-switch"
          type="button"
          @click="switchAuthMode(authMode === 'login' ? 'register' : 'login')"
        >
          {{
            authMode === "login" ? "还没有账号？先注册" : "已经有账号？去登录"
          }}
        </button>
        <p class="auth-disclaimer">
          这是本机演示账号：绑定不发送验证码，也没有真实身份验证。请使用测试联系方式和密码，正式认证需接入后端。
        </p>
      </form>
    </div>
  </div>
  <div
    v-else
    class="app-shell"
    :style="{ '--message-size': `${data.preferences.fontSize}px` }"
  >
    <nav class="rail" aria-label="主导航">
      <div class="rail-logo" title="IM 助手">
        <AppIcon name="sparkles" :size="22" />
      </div>
      <div class="rail-links">
        <button
          v-for="n in nav"
          :key="n.id"
          :class="['rail-link', { active: page === n.id }]"
          :aria-label="n.label"
          :aria-current="page === n.id ? 'page' : undefined"
          :title="n.label"
          @click="page = n.id"
        >
          <AppIcon :name="n.icon" :size="21" /><span
            v-if="
              (n.id === 'requests' &&
                received.some((r) => r.state === 'pending')) ||
              (n.id === 'notices' && unread)
            "
            class="rail-dot"
          />
        </button>
      </div>
      <button
        class="rail-profile"
        aria-label="个人设置"
        @click="page = 'settings'"
      >
        <span class="avatar violet tiny">{{ data.displayName[0] }}</span>
      </button>
    </nav>
    <div v-if="page === 'chat'" class="chat-layout">
      <aside class="conversation-panel">
        <div class="panel-top">
          <div>
            <p class="overline">WORKSPACE</p>
            <h1>
              消息
              <span class="count-pill">{{ data.conversations.length }}</span>
            </h1>
          </div>
          <button
            class="icon-button outlined"
            title="创建讨论室"
            aria-label="创建讨论室"
            @click="openModal('group')"
          >
            <AppIcon name="plus" />
          </button>
        </div>
        <div class="search-box">
          <AppIcon name="search" :size="17" /><input
            v-model="search"
            aria-label="搜索会话或消息"
            placeholder="搜索会话或消息"
          /><button
            v-if="search"
            class="clear-button"
            aria-label="清除搜索"
            @click="search = ''"
          >
            ×
          </button>
        </div>
        <div class="section-label">
          <span>最近会话</span><span>{{ conversations.length }}</span>
        </div>
        <div class="conversation-scroll">
          <button
            v-for="c in conversations"
            :key="c.id"
            :class="['conversation-item', { selected: activeId === c.id }]"
            @click="openChat(c.id)"
          >
            <span :class="['avatar', c.color]">{{ c.initials }}</span
            ><span class="conversation-content"
              ><span class="conversation-line"
                ><strong>{{ c.name }}</strong
                ><small>{{ last(c)?.time || "刚刚" }}</small></span
              ><span class="conversation-line preview"
                ><span
                  >{{
                    last(c)?.senderId === "me" && last(c)?.kind !== "system"
                      ? "我："
                      : ""
                  }}{{ last(c)?.text || "开始一段对话" }}</span
                ><span v-if="c.unread" class="unread-badge">{{
                  c.unread
                }}</span></span
              ></span
            >
          </button>
          <div v-if="!conversations.length" class="empty-list">
            没有找到相关会话
          </div>
        </div>
        <div class="sidebar-bottom">
          <div class="sidebar-help">
            <span class="help-icon"
              ><AppIcon name="sparkles" :size="17"
            /></span>
            <div>
              <strong>好好聊，轻松做</strong>
              <p>从对话中发现下一步</p>
            </div>
          </div>
          <span class="demo-chip"
            ><span class="status-dot" />本地演示 · 接口待接入</span
          >
        </div>
      </aside>
      <main
        v-if="active"
        :class="['chat-main', { 'mobile-open': mobileChatOpen }]"
      >
        <header class="chat-header">
          <div class="chat-heading">
            <button
              class="icon-button mobile-back"
              aria-label="返回会话列表"
              @click="mobileChatOpen = false"
            >
              <AppIcon name="left" /></button
            ><span :class="['avatar', active.color, 'header-avatar']">{{
              active.initials
            }}</span>
            <div>
              <div class="title-row">
                <h2>{{ active.name }}</h2>
                <span v-if="active.kind === 'group'" class="tag">讨论室</span>
              </div>
              <p>
                {{
                  active.kind === "group"
                    ? `${active.memberIds.length} 位成员 · ${active.description || "一起协作"}`
                    : canWrite
                      ? `@${person(active.memberIds.find((id) => id !== "me") || "me").handle} · 好友`
                      : "已解除好友关系 · 历史消息只读"
                }}
              </p>
            </div>
          </div>
          <div class="header-actions">
            <button
              class="icon-button"
              title="接上讨论"
              aria-label="打开讨论助手"
              @click="assistantOpen = !assistantOpen"
            >
              <AppIcon name="sparkles" /></button
            ><button
              class="icon-button"
              title="会话信息"
              aria-label="打开会话信息"
              @click="infoOpen = true"
            >
              <AppIcon name="info" />
            </button>
          </div>
        </header>
        <div class="chat-notice">
          <AppIcon name="shield" :size="15" />
          消息仅在此浏览器演示；真实同步、翻译与 AI 总结待接入后端。
        </div>
        <div ref="messageScroll" class="message-scroll">
          <div class="messages-inner">
            <button
              v-if="!showOlder && messages.length > 12"
              class="load-more"
              @click="showOlder = true"
            >
              查看更早消息
            </button>
            <div class="day-divider"><span>今天 · 讨论继续</span></div>
            <template v-for="m in visibleMessages" :key="m.id"
              ><div v-if="unreadMarker === m.id" class="unread-divider">
                以下是未读消息
              </div>
              <div
                v-if="m.kind === 'system'"
                :id="`msg-${m.id}`"
                class="system-message"
              >
                {{ m.text }}
              </div>
              <div
                v-else
                :id="`msg-${m.id}`"
                :class="[
                  'message-row',
                  { mine: m.senderId === 'me', focused: focusId === m.id },
                ]"
              >
                <span
                  v-if="m.senderId !== 'me'"
                  :class="[
                    'avatar',
                    person(m.senderId).color,
                    'message-avatar',
                  ]"
                  >{{ person(m.senderId).initials }}</span
                >
                <div class="message-stack">
                  <div class="message-meta">
                    <span>{{
                      m.senderId === "me" ? "我" : person(m.senderId).name
                    }}</span
                    ><time>{{ m.time }}</time>
                  </div>
                  <div class="bubble">
                    <span v-if="m.kind === 'voice'" class="voice-label"
                      ><AppIcon name="mic" :size="14" /> 语音转写</span
                    >{{ m.text }}
                  </div>
                  <div
                    v-if="m.translation && data.preferences.showTranslations"
                    class="translation"
                  >
                    <AppIcon name="globe" :size="13" /> 译文 ·
                    {{ m.translation }}
                  </div>
                  <div v-if="m.status" class="delivery">仅本地 · 未同步</div>
                </div>
              </div></template
            >
          </div>
        </div>
        <div class="composer-zone">
          <div v-if="voiceActive" class="voice-interim" role="status">
            <span class="recording-dot" /> 正在转写，仅你可见
            {{ interim ? `· ${interim}` : "· 请开始说话" }}
            <button @click="stopVoice">停止</button>
          </div>
          <div v-if="!canWrite" class="read-only">
            <AppIcon name="lock" :size="16" /> 已解除好友关系，此对话为只读
          </div>
          <div v-else class="composer">
            <textarea
              v-model="composer"
              rows="2"
              aria-label="输入消息"
              placeholder="写点什么，继续这段讨论…"
              @keydown.enter.exact.prevent="send"
            />
            <div class="composer-tools">
              <div class="tool-left">
                <button
                  class="tool-button"
                  :class="{ recording: voiceActive }"
                  :title="voiceActive ? '停止语音转写' : '语音转写（手动发送）'"
                  :aria-label="voiceActive ? '停止语音转写' : '开始语音转写'"
                  @click="toggleVoice"
                >
                  <AppIcon name="mic" :size="18" /></button
                ><button
                  class="tool-button"
                  title="提及成员"
                  aria-label="提及成员"
                  @click="composer += '@'"
                >
                  <AppIcon name="at" :size="18" /></button
                ><span class="composer-tip"
                  >Enter 发送 · Shift + Enter 换行</span
                >
              </div>
              <button
                class="send-button"
                :disabled="!composer.trim()"
                @click="send"
              >
                发送 <AppIcon name="arrowUp" :size="16" />
              </button>
            </div>
          </div>
        </div>
      </main>
      <aside :class="['assistant-panel', { open: assistantOpen }]">
        <div class="assistant-header">
          <div>
            <div class="assistant-brand">
              <span class="assistant-mark"
                ><AppIcon name="sparkles" :size="17" /></span
              ><strong>接上讨论</strong><span class="beta">演示</span>
            </div>
            <p>从消息里理清思路，接住下一步。</p>
          </div>
          <button
            class="icon-button close-assistant"
            aria-label="关闭助手"
            @click="assistantOpen = false"
          >
            <AppIcon name="x" />
          </button>
        </div>
        <div class="assistant-scroll">
          <div class="assist-hero">
            <span class="assist-hero-icon"
              ><AppIcon name="sparkles" :size="23"
            /></span>
            <p>让重要的事<br /><strong>自然浮现。</strong></p>
            <small>选择会话，整理讨论中的线索。</small>
          </div>
          <div class="assist-section">
            <div class="assist-label">
              <AppIcon name="alignLeft" :size="17" /><strong>对话摘要</strong>
            </div>
            <p class="assist-description">
              基于演示消息展示来源；真实 AI 分析待接入。
            </p>
            <div class="segmented">
              <button
                :class="{ active: range === 'recent' }"
                @click="
                  range = 'recent';
                  showSummary = false;
                "
              >
                最近 5 条</button
              ><button
                :class="{ active: range === 'all' }"
                @click="
                  range = 'all';
                  showSummary = false;
                "
              >
                全部消息
              </button>
            </div>
            <button class="soft-button" @click="showSummary = true">
              <AppIcon name="sparkles" :size="15" /> 查看讨论线索
              <AppIcon name="arrowRight" :size="15" />
            </button>
            <div v-if="showSummary" class="summary-card">
              <small
                >消息线索 ·
                {{ range === "recent" ? "最近 5 条" : "全部消息" }}</small
              >
              <p v-if="!summarySources.length">当前范围暂无可整理的消息。</p>
              <template v-else
                ><p>以下是值得回看的发言，点击定位到原消息：</p>
                <button
                  v-for="m in summarySources"
                  :key="m.id"
                  class="source-link"
                  @click="openChat(m.conversationId, m.id)"
                >
                  {{ person(m.senderId).name }}：{{ m.text.slice(0, 38)
                  }}{{ m.text.length > 38 ? "…" : "" }}
                  <AppIcon name="arrowUpRight" :size="14" /></button
              ></template>
            </div>
          </div>
          <div class="assist-section">
            <div class="assist-label">
              <AppIcon name="checkSquare" :size="17" /><strong
                >记下下一步</strong
              >
            </div>
            <p class="assist-description">新建待办需要你亲自确认。</p>
            <input
              v-model="taskDraft"
              class="task-draft"
              aria-label="待办标题"
              placeholder="例如：确认本周接口时间"
              maxlength="100"
              @keydown.enter="addTask"
            /><button
              class="soft-button"
              :disabled="!taskDraft.trim()"
              @click="addTask"
            >
              <AppIcon name="plus" :size="15" /> 确认创建待办
            </button>
          </div>
          <div class="assist-section highlight-section">
            <div class="assist-label">
              <AppIcon name="target" :size="17" /><strong>关注词</strong>
            </div>
            <p>
              当前会话有 <strong>{{ focusCount }}</strong> 条消息提到关注词。
            </p>
            <button
              @click="
                page = 'settings';
                assistantOpen = false;
              "
            >
              管理关注词 <AppIcon name="arrowRight" :size="14" />
            </button>
          </div>
        </div>
        <div class="assistant-footer">
          <span class="status-dot" /> 摘要、翻译、同步待接入接口
        </div>
      </aside>
    </div>
    <main v-else class="page-shell">
      <header class="page-header">
        <div>
          <p class="overline">
            IM ASSISTANT / {{ nav.find((n) => n.id === page)?.label }}
          </p>
          <h1>{{ nav.find((n) => n.id === page)?.label }}</h1>
          <p class="page-subtitle">
            {{
              {
                contacts: "熟悉的人，让协作更顺畅。",
                requests: "从一次打招呼，开始一段好对话。",
                notices: "重要动态，随时掌握。",
                tasks: "把讨论中的下一步妥帖地记下来。",
                settings: "把工作空间调整到喜欢的样子。",
              }[page]
            }}
          </p>
        </div>
        <button
          v-if="page === 'contacts' || page === 'requests'"
          class="primary-button"
          @click="openModal('friend')"
        >
          <AppIcon name="userPlus" :size="17" /> 添加好友
        </button>
      </header>
      <div v-if="page === 'contacts'" class="page-content">
        <div class="stat-row">
          <div class="stat-card">
            <span>我的好友</span
            ><strong>{{ friends.length.toString().padStart(2, "0") }}</strong
            ><small>随时可以开始聊天</small>
          </div>
          <div class="stat-card">
            <span>共同讨论室</span
            ><strong>{{
              data.conversations
                .filter((c) => c.kind === "group")
                .length.toString()
                .padStart(2, "0")
            }}</strong
            ><small>和伙伴一起推进想法</small>
          </div>
        </div>
        <div class="content-card">
          <div class="card-heading">
            <h2>好友列表</h2>
            <span>{{ friends.length }} 位好友</span>
          </div>
          <div v-for="p in friends" :key="p.id" class="person-row">
            <span :class="['avatar', p.color]">{{ p.initials }}</span>
            <div class="person-detail">
              <strong
                >{{ p.name }} <span :class="['presence', p.status]" /></strong
              ><span>@{{ p.handle }} · {{ p.note }}</span>
            </div>
            <button class="outline-button" @click="startDirect(p)">
              <AppIcon name="message" :size="15" /> 发消息</button
            ><button
              class="icon-button subdued"
              :aria-label="`移除好友 ${p.name}`"
              @click="removeFriend(p)"
            >
              <AppIcon name="userMinus" :size="17" />
            </button>
          </div>
        </div>
      </div>
      <div v-if="page === 'requests'" class="page-content">
        <div class="content-card">
          <div class="card-heading">
            <h2>收到的申请</h2>
            <span
              >{{
                received.filter((r) => r.state === "pending").length
              }}
              条待处理</span
            >
          </div>
          <div v-if="!received.length" class="empty-state">
            <AppIcon name="inbox" :size="26" /><strong>暂时没有新申请</strong>
            <p>新的好友邀请会出现在这里。</p>
          </div>
          <div v-for="r in received" :key="r.id" class="request-row">
            <span :class="['avatar', person(r.fromId).color]">{{
              person(r.fromId).initials
            }}</span>
            <div class="request-body">
              <strong
                >{{ person(r.fromId).name }}
                <span>@{{ person(r.fromId).handle }}</span></strong
              >
              <p>{{ r.note }}</p>
              <small
                >{{ r.time }} ·
                {{
                  r.state === "pending"
                    ? "等待你处理"
                    : r.state === "accepted"
                      ? "已同意"
                      : "已拒绝"
                }}</small
              >
            </div>
            <div v-if="r.state === 'pending'" class="row-actions">
              <button
                class="outline-button"
                @click="resolveRequest(r.id, 'rejected')"
              >
                拒绝</button
              ><button
                class="primary-button compact"
                @click="resolveRequest(r.id, 'accepted')"
              >
                同意
              </button>
            </div>
          </div>
        </div>
        <div class="content-card">
          <div class="card-heading">
            <h2>我发出的申请</h2>
            <span
              >{{
                sent.filter((r) => r.state === "pending").length
              }}
              条待回应</span
            >
          </div>
          <div v-if="!sent.length" class="empty-state mini">
            还没有发出的申请。通过唯一账号搜索新朋友吧。
          </div>
          <div v-for="r in sent" :key="r.id" class="request-row">
            <span :class="['avatar', person(r.toId).color]">{{
              person(r.toId).initials
            }}</span>
            <div class="request-body">
              <strong
                >{{ person(r.toId).name }}
                <span>@{{ person(r.toId).handle }}</span></strong
              >
              <p>{{ r.note }}</p>
              <small
                >{{ r.time }} ·
                {{ r.state === "pending" ? "等待回应" : "已撤回" }}</small
              >
            </div>
            <button
              v-if="r.state === 'pending'"
              class="text-button"
              @click="resolveRequest(r.id, 'withdrawn')"
            >
              撤回
            </button>
          </div>
        </div>
      </div>
      <div v-if="page === 'notices'" class="page-content">
        <div class="content-card">
          <div class="card-heading">
            <h2>最近动态</h2>
            <button
              class="text-button"
              @click="data.notices.forEach((n) => (n.read = true))"
            >
              全部标为已读
            </button>
          </div>
          <button
            v-for="n in data.notices"
            :key="n.id"
            :class="['notice-row', { unread: !n.read }]"
            @click="openNotice(n.id)"
          >
            <span class="notice-icon"
              ><AppIcon
                :name="
                  n.type === 'mention'
                    ? 'at'
                    : n.type === 'activity'
                      ? 'clock'
                      : 'sparkles'
                "
                :size="19" /></span
            ><span class="notice-body"
              ><strong>{{ n.title }}</strong
              ><span>{{ n.description }}</span></span
            ><small>{{ n.time }}</small
            ><span v-if="!n.read" class="notice-dot" />
          </button>
        </div>
      </div>
      <div v-if="page === 'tasks'" class="page-content">
        <div class="stat-row">
          <div class="stat-card">
            <span>进行中</span
            ><strong>{{ openTasks.toString().padStart(2, "0") }}</strong
            ><small>一次做好一件事</small>
          </div>
          <div class="stat-card">
            <span>已完成</span
            ><strong>{{
              data.todos
                .filter((t) => t.done)
                .length.toString()
                .padStart(2, "0")
            }}</strong
            ><small>每一步都值得记录</small>
          </div>
        </div>
        <div class="content-card">
          <div class="card-heading">
            <h2>我的待办</h2>
            <span>共 {{ data.todos.length }} 项</span>
          </div>
          <div class="add-task">
            <AppIcon name="plus" :size="18" /><input
              v-model="taskDraft"
              maxlength="100"
              aria-label="新待办标题"
              placeholder="添加一项待办，按 Enter 确认"
              @keydown.enter="addTask"
            /><button :disabled="!taskDraft.trim()" @click="addTask">
              添加
            </button>
          </div>
          <div
            v-for="t in data.todos"
            :key="t.id"
            :class="['todo-row', { done: t.done }]"
          >
            <label class="todo-label"
              ><input
                v-model="t.done"
                type="checkbox"
                :aria-label="`完成 ${t.title}`"
              /><span class="custom-check"
                ><AppIcon name="check" :size="13" /></span
              ><span>{{ t.title }}</span></label
            ><span class="due">{{ t.due }}</span
            ><button
              v-if="t.sourceConversationId"
              class="source-small"
              @click="openChat(t.sourceConversationId!, t.sourceId)"
            >
              查看来源 <AppIcon name="arrowUpRight" :size="14" /></button
            ><button
              class="icon-button subdued"
              :aria-label="`删除 ${t.title}`"
              @click="data.todos = data.todos.filter((x) => x.id !== t.id)"
            >
              <AppIcon name="trash" :size="16" />
            </button>
          </div>
          <div v-if="!data.todos.length" class="empty-state">
            一切都安排好了
          </div>
        </div>
      </div>
      <div v-if="page === 'settings'" class="page-content settings-content">
        <div class="content-card">
          <div class="card-heading">
            <h2>个人资料</h2>
            <span>本地演示资料</span>
          </div>
          <div class="profile-setting">
            <span class="avatar violet large">{{ data.displayName[0] }}</span>
            <div>
              <strong>{{ data.displayName }}</strong
              ><span>@{{ data.handle }}</span>
            </div>
            <button class="outline-button" @click="signOut">退出登录</button>
          </div>
          <div class="account-contacts">
            <div>
              <span>手机号</span
              ><strong>{{ currentAccount?.phone || "未绑定" }}</strong>
            </div>
            <div>
              <span>邮箱</span
              ><strong>{{ currentAccount?.email || "未绑定" }}</strong>
            </div>
          </div>
          <form
            v-if="missingContact"
            class="bind-form"
            @submit.prevent="bindContact"
          >
            <strong
              >补绑{{ missingContact === "phone" ? "手机号" : "邮箱" }}</strong
            >
            <p>确认当前密码后可在本机将另一种联系方式关联到此账号。</p>
            <label for="bind-value">{{
              missingContact === "phone" ? "手机号" : "邮箱地址"
            }}</label>
            <input
              id="bind-value"
              v-model="bindValue"
              :type="missingContact === 'phone' ? 'tel' : 'email'"
              :autocomplete="missingContact === 'phone' ? 'tel' : 'email'"
              :placeholder="
                missingContact === 'phone'
                  ? '请输入 11 位手机号'
                  : 'name@example.com'
              "
              required
            />
            <label for="bind-password">当前密码</label>
            <input
              id="bind-password"
              v-model="bindPassword"
              type="password"
              autocomplete="current-password"
              placeholder="输入当前账号密码"
              required
            />
            <p v-if="bindError" class="error-text" role="alert">
              {{ bindError }}
            </p>
            <button class="primary-button" type="submit" :disabled="bindBusy">
              {{ bindBusy ? "处理中…" : "关联到当前账号" }}
            </button>
            <small
              >演示绑定不验证号码或邮箱所有权；正式产品需要短信或邮件验证码。</small
            >
          </form>
          <p v-if="bindSuccess" class="success-text" role="status">
            {{ bindSuccess }}
          </p>
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
              <p>逗号分隔，助手可提示相关消息</p>
            </div>
            <input
              v-model="data.preferences.focusWords"
              class="setting-input"
              aria-label="关注词"
            />
          </div>
          <div class="setting-row">
            <div>
              <strong>免打扰时段</strong>
              <p>偏好已保存，推送能力待接入</p>
            </div>
            <label class="switch"
              ><input
                v-model="data.preferences.quietHours"
                type="checkbox"
                aria-label="免打扰时段" /><span
            /></label>
          </div>
        </div>
        <div class="content-card">
          <div class="card-heading"><h2>语言与显示</h2></div>
          <div class="setting-row">
            <div>
              <strong>默认翻译目标</strong>
              <p>翻译服务接入后使用</p>
            </div>
            <select
              v-model="data.preferences.translationTarget"
              aria-label="默认翻译目标"
            >
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
              ><input
                v-model="data.preferences.showTranslations"
                type="checkbox"
                aria-label="显示译文" /><span
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
            <p>所有交互保存在此浏览器本地。</p>
          </div>
          <button class="outline-button" @click="reset">重置演示数据</button>
        </div>
      </div>
    </main>
    <nav v-if="page !== 'chat'" class="mobile-nav" aria-label="移动端导航">
      <button
        v-for="n in nav"
        :key="n.id"
        :class="{ active: page === n.id }"
        @click="page = n.id"
      >
        <AppIcon :name="n.icon" :size="19" /><span>{{ n.label }}</span>
      </button>
    </nav>
    <div
      v-if="infoOpen && active"
      class="drawer-backdrop"
      @click.self="infoOpen = false"
    >
      <aside
        class="info-drawer"
        role="dialog"
        aria-modal="true"
        aria-label="会话信息"
      >
        <header>
          <h2>会话信息</h2>
          <button
            class="icon-button"
            aria-label="关闭"
            @click="infoOpen = false"
          >
            <AppIcon name="x" />
          </button>
        </header>
        <div class="drawer-identity">
          <span :class="['avatar', active.color, 'large']">{{
            active.initials
          }}</span>
          <h3>{{ active.name }}</h3>
          <p>
            {{ active.description || "保持联系，让每一次对话都更有意义。" }}
          </p>
        </div>
        <template v-if="active.kind === 'group'"
          ><div class="drawer-section-heading">
            <strong>成员 · {{ active.memberIds.length }}/50</strong
            ><button
              v-if="active.ownerId === 'me'"
              class="text-button"
              @click="
                infoOpen = false;
                openModal('invite');
              "
            >
              邀请好友
            </button>
          </div>
          <div v-for="id in active.memberIds" :key="id" class="member-row">
            <span :class="['avatar', person(id).color, 'tiny']">{{
              person(id).initials
            }}</span
            ><span>{{ person(id).name }}</span
            ><small v-if="active.ownerId === id">群主</small>
          </div>
          <p v-if="active.ownerId !== 'me'" class="drawer-hint">
            仅群主可邀请自己的好友。
          </p></template
        >
        <div class="drawer-section-heading"><strong>会话偏好</strong></div>
        <label class="drawer-toggle"
          >置顶会话 <input v-model="active.pinned" type="checkbox" /></label
        ><label class="drawer-toggle"
          >消息免打扰 <input v-model="active.muted" type="checkbox"
        /></label>
      </aside>
    </div>
    <div v-if="modal" class="modal-backdrop" @click.self="modal = null">
      <div
        class="modal-card"
        role="dialog"
        aria-modal="true"
        :aria-label="
          modal === 'group'
            ? '创建讨论室'
            : modal === 'invite'
              ? '邀请好友'
              : '添加好友'
        "
      >
        <div class="modal-heading">
          <div>
            <span class="modal-icon"
              ><AppIcon :name="modal === 'friend' ? 'userPlus' : 'users'"
            /></span>
            <h2>
              {{
                modal === "group"
                  ? "创建讨论室"
                  : modal === "invite"
                    ? "邀请好友加入"
                    : "添加好友"
              }}
            </h2>
          </div>
          <button
            class="icon-button"
            aria-label="关闭弹窗"
            @click="modal = null"
          >
            <AppIcon name="x" />
          </button>
        </div>
        <template v-if="modal === 'friend'"
          ><p class="modal-lead">
            通过唯一账号找到伙伴。演示可尝试 @heyutong。
          </p>
          <label class="field-label" for="friend-handle">对方账号</label>
          <div class="input-prefix">
            <span>@</span
            ><input
              id="friend-handle"
              v-model="friendHandle"
              placeholder="输入唯一账号"
              @keydown.enter="requestFriend"
            />
          </div>
          <label class="field-label" for="friend-note"
            >打个招呼 <span>选填</span></label
          ><textarea
            id="friend-note"
            v-model="friendNote"
            rows="3"
            maxlength="120"
            placeholder="介绍一下自己吧…"
          /><button
            class="primary-button full modal-submit"
            :disabled="!friendHandle.trim()"
            @click="requestFriend"
          >
            发送好友申请
          </button></template
        ><template v-else
          ><p class="modal-lead">
            {{
              modal === "group"
                ? "取一个好名字，再邀请好友加入讨论。"
                : "仅群主可邀请自己的好友，每次最多 20 人。"
            }}
          </p>
          <template v-if="modal === 'group'"
            ><label class="field-label" for="group-name">讨论室名称</label
            ><input
              id="group-name"
              v-model="groupName"
              maxlength="30"
              placeholder="例如：产品共创讨论室"
          /></template>
          <div class="field-label">
            选择好友 <span>已选 {{ inviteIds.length }} 人</span>
          </div>
          <div class="invite-list">
            <label
              v-for="p in modal === 'invite' ? candidates : friends"
              :key="p.id"
              class="invite-person"
              ><span :class="['avatar', p.color, 'tiny']">{{ p.initials }}</span
              ><span
                >{{ p.name }} <small>@{{ p.handle }}</small></span
              ><input
                type="checkbox"
                :checked="inviteIds.includes(p.id)"
                :disabled="
                  !inviteIds.includes(p.id) &&
                  (inviteIds.length >= 20 ||
                    (modal === 'invite' &&
                      !!active &&
                      active.memberIds.length + inviteIds.length >= 50))
                "
                @change="toggleInvite(p.id)"
            /></label>
            <p
              v-if="modal === 'invite' && !candidates.length"
              class="empty-list"
            >
              好友都已在讨论室中
            </p>
          </div>
          <button
            class="primary-button full modal-submit"
            :disabled="
              modal === 'group' ? !groupName.trim() : !inviteIds.length
            "
            @click="modal === 'group' ? createGroup() : addMembers()"
          >
            {{ modal === "group" ? "创建讨论室" : "确认邀请" }}
          </button></template
        >
      </div>
    </div>
    <div v-if="toast" class="toast" role="status">
      <AppIcon name="info" :size="17" />{{ toast }}
    </div>
  </div>
</template>
