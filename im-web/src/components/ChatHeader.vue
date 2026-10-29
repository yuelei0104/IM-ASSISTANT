<script setup lang="ts">
import AppIcon from "./AppIcon.vue";
import { chinaDateTime } from "../chinaTime";
import type { Conversation, Person } from "../demo";
import type { ContextMessage } from "../useMessageContext";

const props = defineProps<{
  active: Conversation;
  isDirect: boolean;
  isGroup: boolean;
  canWrite: boolean;
  selfAccountId: number | null;
  contextActive: boolean;
  assistantOpen: boolean;

  historyTerm: string;
  historyResults: ContextMessage[];
  historySearching: boolean;
  historySearchError: string;
  historyNextBeforeId: number | null;

  person: (id: string) => Person;
}>();

/** The search panel keeps its query while closed, so it is owned by the shell. */
const historyOpen = defineModel<boolean>("historyOpen", { required: true });
const historyQuery = defineModel<string>("historyQuery", { required: true });

const emit = defineEmits<{
  back: [];
  deleteConversation: [];
  search: [beforeId?: number];
  closeHistory: [];
  openContext: [message: ContextMessage];
  toggleAssistant: [];
  openInfo: [];
  closeContext: [];
}>();

function senderName(m: ContextMessage) {
  return m.senderId === props.selfAccountId ? "我" : props.person(`user-${m.senderId}`).name;
}
</script>

<template>
  <header class="chat-header">
    <div class="chat-heading">
      <button class="icon-button mobile-back" aria-label="返回会话列表" @click="emit('back')">
        <AppIcon name="left" /></button
      ><span :class="['avatar', active.color, 'header-avatar']">{{ active.initials }}</span>
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
        v-if="isDirect"
        class="icon-button"
        title="删除我的会话"
        aria-label="删除我的会话"
        @click="emit('deleteConversation')"
      >
        <AppIcon name="trash" /></button
      ><button
        v-if="isDirect || isGroup"
        class="icon-button"
        title="搜索当前会话的历史消息"
        aria-label="搜索当前会话的历史消息"
        :aria-expanded="historyOpen"
        @click="historyOpen ? emit('closeHistory') : (historyOpen = true)"
      >
        <AppIcon name="search" /></button
      ><button
        class="icon-button"
        title="接上讨论"
        aria-label="打开讨论助手"
        :aria-expanded="assistantOpen"
        @click="emit('toggleAssistant')"
      >
        <AppIcon name="sparkles" /></button
      ><button class="icon-button" title="会话信息" aria-label="打开会话信息" @click="emit('openInfo')">
        <AppIcon name="info" />
      </button>
    </div>
  </header>
  <section v-if="historyOpen" class="history-search" aria-label="当前会话历史消息搜索">
    <div class="history-search-heading">
      <strong>搜索 {{ active.name }} 的历史消息</strong>
      <button class="text-button" type="button" @click="emit('closeHistory')">关闭</button>
    </div>
    <form class="history-search-form" @submit.prevent="emit('search')">
      <input v-model="historyQuery" maxlength="50" aria-label="消息关键词" placeholder="输入至少 2 个字符" />
      <button type="submit" :disabled="historySearching || historyQuery.trim().length < 2">
        {{ historySearching ? "搜索中…" : "搜索" }}
      </button>
    </form>
    <p v-if="historySearchError" class="error-text" role="alert">{{ historySearchError }}</p>
    <div v-if="historyTerm" class="history-results" aria-live="polite">
      <p v-if="!historySearching && !historyResults.length && !historySearchError" class="history-empty">
        没有找到包含“{{ historyTerm }}”的消息
      </p>
      <article v-for="m in historyResults" :key="m.id" class="history-result">
        <div>
          <strong>{{ senderName(m) }}</strong>
          <time>{{ chinaDateTime(m.createdAt) }}</time>
        </div>
        <p>{{ m.body }}</p>
        <button type="button" class="text-button" @click="emit('openContext', m)">查看上下文</button>
      </article>
      <button
        v-if="historyNextBeforeId"
        class="load-more"
        type="button"
        :disabled="historySearching"
        @click="emit('search', historyNextBeforeId)"
      >
        {{ historySearching ? "加载中…" : "加载更早结果" }}
      </button>
    </div>
  </section>
  <div class="chat-notice">
    <AppIcon name="shield" :size="15" />
    消息已保存；在线时实时推送，断线后自动补拉。群消息可请求本机翻译并核对后发送。
  </div>
  <div v-if="contextActive" class="context-banner">
    <span>正在查看搜索结果附近的消息 · 新消息仍会在后台同步</span>
    <button type="button" @click="emit('closeContext')">返回最新消息</button>
  </div>
</template>
