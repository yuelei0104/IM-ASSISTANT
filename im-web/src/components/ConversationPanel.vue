<script setup lang="ts">
import AppIcon from "./AppIcon.vue";
import type { Conversation, Message } from "../demo";

const props = defineProps<{
  conversations: Conversation[];
  /** Total count before the local filter, so the header keeps showing the real number. */
  totalCount: number;
  activeId: string;
  /** The open conversation's newest message; the list previews it inline instead of refetching. */
  activePreview: Message | null;
  hasMoreGroups: boolean;
}>();

const search = defineModel<string>("search", { required: true });

const emit = defineEmits<{
  open: [id: string];
  createGroup: [];
  loadMoreGroups: [];
}>();

function preview(id: string) {
  return id === props.activeId ? props.activePreview : null;
}

function time(c: Conversation) {
  return preview(c.id)?.time || "刚刚";
}

function text(c: Conversation) {
  return preview(c.id)?.text || "开始一段对话";
}

function speakerPrefix(c: Conversation) {
  const last = preview(c.id);
  return last?.senderId === "me" && last?.kind !== "system" ? "我：" : "";
}

function unreadLabel(c: Conversation) {
  return c.unread ? `${c.name}，${c.unread} 条未读消息` : c.name;
}
</script>

<template>
  <aside class="conversation-panel">
    <div class="panel-top">
      <div>
        <p class="overline">WORKSPACE</p>
        <h1>
          消息
          <span class="count-pill">{{ totalCount }}</span>
        </h1>
      </div>
      <button
        class="icon-button outlined"
        title="创建讨论室"
        aria-label="创建讨论室"
        @click="emit('createGroup')"
      >
        <AppIcon name="plus" />
      </button>
    </div>
    <div class="search-box">
      <AppIcon name="search" :size="17" /><input
        v-model="search"
        aria-label="筛选会话"
        placeholder="筛选会话"
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
        :aria-label="unreadLabel(c)"
        @click="emit('open', c.id)"
      >
        <span :class="['avatar', c.color]">{{ c.initials }}</span
        ><span class="conversation-content"
          ><span class="conversation-line"
            ><strong>{{ c.name }}</strong
            ><small>{{ time(c) }}</small></span
          ><span class="conversation-line preview"
            ><span>{{ speakerPrefix(c) }}{{ text(c) }}</span
            ><span v-if="c.unread" class="unread-badge">{{
              c.unread > 99 ? "99+" : c.unread
            }}</span></span
          ></span
        >
      </button>
      <button v-if="hasMoreGroups" class="load-more" @click="emit('loadMoreGroups')">
        加载更多讨论室
      </button>
      <div v-if="!conversations.length" class="empty-list">没有找到相关会话</div>
    </div>
    <div class="sidebar-bottom">
      <div class="sidebar-help">
        <span class="help-icon"><AppIcon name="sparkles" :size="17" /></span>
        <div>
          <strong>好好聊，轻松做</strong>
          <p>从对话中发现下一步</p>
        </div>
      </div>
      <span class="demo-chip"><span class="status-dot" />好友私聊与讨论室已接入</span>
    </div>
  </aside>
</template>
