<script setup lang="ts">
import AppIcon from "./AppIcon.vue";
import type { Conversation, Person } from "../demo";

const props = defineProps<{
  active: Conversation;
  groupBusy: boolean;
  person: (id: string) => Person;
}>();

const open = defineModel<boolean>("open", { required: true });

const emit = defineEmits<{
  invite: [];
  transferOwner: [memberId: string];
  removeMember: [memberId: string];
  leave: [];
  dissolve: [];
}>();
</script>

<template>
  <div v-if="open" class="drawer-backdrop" @click.self="open = false">
    <aside class="info-drawer" role="dialog" aria-modal="true" aria-label="会话信息">
      <header>
        <h2>会话信息</h2>
        <button class="icon-button" aria-label="关闭" @click="open = false">
          <AppIcon name="x" />
        </button>
      </header>
      <div class="drawer-identity">
        <span :class="['avatar', active.color, 'large']">{{ active.initials }}</span>
        <h3>{{ active.name }}</h3>
        <p>{{ active.description || "保持联系，让每一次对话都更有意义。" }}</p>
      </div>
      <template v-if="active.kind === 'group'"
        ><div class="drawer-section-heading">
          <strong>成员 · {{ active.memberIds.length }}/50</strong
          ><button
            v-if="active.ownerId === 'me'"
            class="text-button"
            @click="
              open = false;
              emit('invite');
            "
          >
            邀请好友
          </button>
        </div>
        <div v-for="id in active.memberIds" :key="id" class="member-row">
          <span :class="['avatar', person(id).color, 'tiny']">{{ person(id).initials }}</span
          ><span>{{ person(id).name }}</span
          ><small v-if="active.ownerId === id">群主</small>
          <span v-else-if="active.ownerId === 'me'" class="member-actions">
            <button
              class="text-button"
              :disabled="groupBusy"
              :aria-label="`转让群主给 ${person(id).name}`"
              @click="emit('transferOwner', id)"
            >
              转让群主</button
            ><button
              class="text-button"
              :disabled="groupBusy"
              :aria-label="`移出 ${person(id).name}`"
              @click="emit('removeMember', id)"
            >
              移出
            </button>
          </span>
        </div>
        <p v-if="active.ownerId !== 'me'" class="drawer-hint">仅群主可邀请自己的好友。</p>
        <button
          v-if="active.ownerId !== 'me'"
          class="text-button drawer-leave"
          :disabled="groupBusy"
          @click="emit('leave')"
        >
          退出讨论室</button
        ><button v-else class="text-button drawer-dissolve" :disabled="groupBusy" @click="emit('dissolve')">
          解散讨论室
        </button></template
      >
      <div class="drawer-section-heading"><strong>会话偏好</strong></div>
      <label class="drawer-toggle">置顶会话 <input v-model="active.pinned" type="checkbox" /></label
      ><label class="drawer-toggle">消息免打扰 <input v-model="active.muted" type="checkbox" /></label>
    </aside>
  </div>
</template>
