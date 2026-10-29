<script setup lang="ts">
import AppIcon from "./AppIcon.vue";
import type { Conversation, Person } from "../demo";

export type ModalKind = "group" | "invite" | "friend" | null;

const props = defineProps<{
  candidates: Person[];
  friends: Person[];
  active: Conversation | null;
  contactsBusy: boolean;
  /** True while a group create/invite request is in flight. */
  groupBusy: boolean;
  friendBusy: boolean;
  hasMoreFriends: boolean;
}>();

const modal = defineModel<ModalKind>("modal", { required: true });
const friendSearchType = defineModel<"phone" | "handle">("friendSearchType", { required: true });
const friendQuery = defineModel<string>("friendQuery", { required: true });
const friendNote = defineModel<string>("friendNote", { required: true });
const friendError = defineModel<string>("friendError", { required: true });
const groupName = defineModel<string>("groupName", { required: true });
const inviteIds = defineModel<string[]>("inviteIds", { required: true });

const emit = defineEmits<{
  requestFriend: [];
  toggleInvite: [id: string];
  loadMoreFriends: [];
  confirm: [kind: "group" | "invite"];
}>();

function title() {
  return modal.value === "group" ? "创建讨论室" : modal.value === "invite" ? "邀请好友加入" : "添加好友";
}

function pick(kind: "phone" | "handle") {
  friendSearchType.value = kind;
  friendQuery.value = "";
  friendError.value = "";
}

function inviteDisabled(person: Person) {
  if (inviteIds.value.includes(person.id)) return false;
  if (inviteIds.value.length >= 20) return true;
  return (
    modal.value === "invite" &&
    !!props.active &&
    props.active.memberIds.length + inviteIds.value.length >= 50
  );
}

function confirmDisabled() {
  if (props.groupBusy) return true;
  return modal.value === "group" ? !groupName.value.trim() : !inviteIds.value.length;
}
</script>

<template>
  <div v-if="modal" class="modal-backdrop" @click.self="modal = null">
    <div class="modal-card" role="dialog" aria-modal="true" :aria-label="title()">
      <div class="modal-heading">
        <div>
          <span class="modal-icon"
            ><AppIcon :name="modal === 'friend' ? 'userPlus' : 'users'"
          /></span>
          <h2>{{ title() }}</h2>
        </div>
        <button class="icon-button" aria-label="关闭弹窗" @click="modal = null">
          <AppIcon name="x" />
        </button>
      </div>
      <template v-if="modal === 'friend'"
        ><p class="modal-lead">
          输入对方已绑定的手机号或唯一账号发送申请。手机号未关联账号时会提示，不展示对方资料。
        </p>
        <div class="auth-tabs" role="group" aria-label="查找方式">
          <button
            type="button"
            :class="{ active: friendSearchType === 'phone' }"
            :aria-pressed="friendSearchType === 'phone'"
            @click="pick('phone')"
          >
            手机号
          </button>
          <button
            type="button"
            :class="{ active: friendSearchType === 'handle' }"
            :aria-pressed="friendSearchType === 'handle'"
            @click="pick('handle')"
          >
            唯一账号
          </button>
        </div>
        <label class="field-label" for="friend-query">{{
          friendSearchType === "phone" ? "对方手机号" : "对方账号"
        }}</label>
        <div v-if="friendSearchType === 'handle'" class="input-prefix">
          <span>@</span
          ><input
            id="friend-query"
            v-model="friendQuery"
            placeholder="输入唯一账号"
            @input="friendError = ''"
            @keydown.enter.prevent="emit('requestFriend')"
          />
        </div>
        <input
          v-else
          id="friend-query"
          v-model="friendQuery"
          type="tel"
          inputmode="tel"
          autocomplete="off"
          placeholder="输入已绑定的手机号"
          @input="friendError = ''"
          @keydown.enter.prevent="emit('requestFriend')"
        />
        <p v-if="friendError" class="error-text" role="alert">{{ friendError }}</p>
        <label class="field-label" for="friend-note">打个招呼 <span>选填</span></label
        ><textarea
          id="friend-note"
          v-model="friendNote"
          rows="3"
          maxlength="120"
          placeholder="介绍一下自己吧…"
        /><button
          class="primary-button full modal-submit"
          :disabled="!friendQuery.trim() || friendBusy"
          @click="emit('requestFriend')"
        >
          发送好友申请
        </button></template
      ><template v-else
        ><p class="modal-lead">
          {{
            modal === "group" ? "取一个好名字，再邀请好友加入讨论。" : "仅群主可邀请自己的好友，每次最多 20 人。"
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
        <p v-if="friendError" class="error-text" role="alert">{{ friendError }}</p>
        <div class="field-label">选择好友 <span>已选 {{ inviteIds.length }} 人</span></div>
        <div class="invite-list">
          <label
            v-for="p in modal === 'invite' ? candidates : friends"
            :key="p.id"
            class="invite-person"
            ><span :class="['avatar', p.color, 'tiny']">{{ p.initials }}</span
            ><span>{{ p.name }} <small>@{{ p.handle }}</small></span
            ><input
              type="checkbox"
              :checked="inviteIds.includes(p.id)"
              :disabled="inviteDisabled(p)"
              @change="emit('toggleInvite', p.id)"
          /></label>
          <p v-if="modal === 'invite' && !candidates.length" class="empty-list">好友都已在讨论室中</p>
        </div>
        <button v-if="hasMoreFriends" class="text-button" type="button" :disabled="contactsBusy" @click="emit('loadMoreFriends')">
          加载更多好友
        </button>
        <button
          class="primary-button full modal-submit"
          :disabled="confirmDisabled()"
          @click="emit('confirm', modal === 'group' ? 'group' : 'invite')"
        >
          {{ modal === "group" ? "创建讨论室" : "确认邀请" }}
        </button></template
      >
    </div>
  </div>
</template>
