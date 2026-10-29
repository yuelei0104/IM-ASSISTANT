<script setup lang="ts">
import AppIcon from "./AppIcon.vue";
import type { Person } from "../demo";
import type { useContacts } from "../useContacts";

const props = defineProps<{
  contacts: ReturnType<typeof useContacts>;
  friends: Person[];
  groupCount: number;
}>();

const emit = defineEmits<{
  openChat: [person: Person];
}>();

const { error: contactsError, busy: contactsBusy, friendsCursor: contactsNextCursor,
  refresh: refreshContacts, loadMoreFriends, remove: removeFriend } = props.contacts;
</script>

<template>
  <div class="page-content">
    <p v-if="contactsError" class="error-text" role="alert">{{ contactsError }}</p>
    <p v-if="contactsBusy" role="status">正在加载好友…</p>
    <div class="stat-row">
      <div class="stat-card">
        <span>我的好友</span
        ><strong>{{ friends.length.toString().padStart(2, "0") }}</strong
        ><small>好友关系已同步</small>
      </div>
      <div class="stat-card">
        <span>共同讨论室</span
        ><strong>{{ groupCount.toString().padStart(2, "0") }}</strong
        ><small>和伙伴一起推进想法</small>
      </div>
    </div>
    <div class="content-card">
      <div class="card-heading">
        <h2>好友列表</h2>
        <span>{{ friends.length }} 位好友</span>
        <button class="text-button" type="button" :disabled="contactsBusy" @click="refreshContacts">
          刷新
        </button>
      </div>
      <div v-for="p in friends" :key="p.id" class="person-row">
        <span :class="['avatar', p.color]">{{ p.initials }}</span>
        <div class="person-detail">
          <strong>{{ p.name }}</strong
          ><span>@{{ p.handle }} · {{ p.note }}</span>
        </div>
        <button class="outline-button" @click="emit('openChat', p)">
          <AppIcon name="message" :size="15" /> 发消息</button
        ><button class="icon-button subdued" :aria-label="`移除好友 ${p.name}`" @click="removeFriend(p)">
          <AppIcon name="userMinus" :size="17" />
        </button>
      </div>
      <div v-if="!friends.length && !contactsBusy" class="empty-state">
        还没有好友，试试通过唯一账号添加。
      </div>
      <button v-if="contactsNextCursor" class="text-button" :disabled="contactsBusy" @click="loadMoreFriends">
        加载更多好友
      </button>
    </div>
  </div>
</template>
