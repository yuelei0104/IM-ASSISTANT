<script setup lang="ts">
import AppIcon from "./AppIcon.vue";
import { chinaDateTime } from "../chinaTime";
import type { MentionNotice } from "../noticeApi";
import type { Person } from "../demo";
import type { useNotices } from "../useNotices";

const props = defineProps<{
  notices: ReturnType<typeof useNotices>;
  items: MentionNotice[];
  unread: number;
  person: (id: string) => Person;
}>();

const { error: noticeError, nextBeforeId: noticeNextBeforeId, refresh: refreshNotices,
  loadOlder: loadOlderNotices, readAll: readAllNotices } = props.notices;

/** Opening a notice navigates into the conversation, so the shell owns it. */
const emit = defineEmits<{ open: [notice: MentionNotice] }>();
</script>

<template>
  <div class="page-content">
    <div class="content-card">
      <div class="card-heading">
        <h2>消息提醒</h2>
        <button class="text-button" :disabled="!unread" @click="readAllNotices">全部标为已读</button>
      </div>
      <p v-if="noticeError" class="error-text" role="alert">
        {{ noticeError }}
        <button class="text-button" @click="refreshNotices">重试</button>
      </p>
      <button
        v-for="n in items"
        :key="n.id"
        :class="['notice-row', { unread: !n.readAt }]"
        @click="emit('open', n)"
      >
        <span class="notice-icon"
          ><AppIcon :name="n.kind === 'KEYWORD' ? 'bell' : 'at'" :size="19" /></span
        ><span class="notice-body"
          ><strong
            >{{ person(`user-${n.senderId}`).name }} 在「{{ n.groupName }}」中{{
              n.kind === "KEYWORD" ? "发了关注词消息" : "提到了你"
            }}</strong
          ><span>{{ n.body.slice(0, 100) }}</span></span
        ><small>{{ chinaDateTime(n.createdAt) }}</small
        ><span v-if="!n.readAt" class="notice-dot" />
      </button>
      <p v-if="!items.length && !noticeError" class="empty-list">还没有点名提醒</p>
      <button v-if="noticeNextBeforeId" class="text-button" @click="loadOlderNotices">加载更早提醒</button>
    </div>
  </div>
</template>
