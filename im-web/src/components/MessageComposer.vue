<script setup lang="ts">
import { computed } from "vue";
import AppIcon from "./AppIcon.vue";
import type { Person } from "../demo";

const props = defineProps<{
  voiceActive: boolean;
  voiceBusy: boolean;
  voiceDraft: string;
  voiceInstallAvailable: boolean;
  interim: string;
  sending: boolean;
  /** Browsing a search context turns the composer read-only until the user returns. */
  contextActive: boolean;
  canWrite: boolean;
  isGroup: boolean;
  mentionIds: string[];
  person: (id: string) => Person;
}>();

const composer = defineModel<string>("composer", { required: true });
const mentionPickerOpen = defineModel<boolean>("mentionPickerOpen", { required: true });

const emit = defineEmits<{
  toggleVoice: [];
  stopVoice: [discard?: boolean];
  acceptVoiceDraft: [];
  installVoicePack: [];
  shareCaption: [fromComposer: boolean];
  mention: [id: string];
  send: [];
}>();

const canSend = computed(() => Boolean(composer.value.trim()) && !props.sending);
const showVoicePanel = computed(
  () => props.voiceActive || props.voiceBusy || Boolean(props.voiceDraft) || props.voiceInstallAvailable,
);
const voiceTitle = computed(() =>
  props.voiceActive ? "正在本机转写" : props.voiceBusy ? "正在准备本机转写" : "语音文字预览",
);
</script>

<template>
  <div class="composer-zone">
    <div v-if="showVoicePanel" class="voice-interim" role="group" aria-label="本机语音转写预览">
      <div class="voice-heading">
        <span v-if="voiceActive" class="recording-dot" />
        <strong>{{ voiceTitle }}</strong>
        <span>预览仅自己可见 · 发送后仅讨论室成员可看</span>
      </div>
      <p v-if="voiceInstallAvailable">浏览器需要下载中文本机语音包，确认后才会开始下载。</p>
      <button v-if="voiceInstallAvailable" type="button" :disabled="voiceBusy" @click="emit('installVoicePack')">
        安装中文语音包
      </button>
      <p v-if="voiceDraft" class="voice-transcript" aria-live="polite">{{ voiceDraft }}</p>
      <p v-if="voiceActive" class="voice-partial">{{ interim || "请开始说话，识别文字会显示在这里…" }}</p>
      <div class="voice-actions">
        <button v-if="voiceActive" type="button" @click="emit('stopVoice', false)">停止录音</button>
        <button v-if="voiceDraft" type="button" @click="emit('acceptVoiceDraft')">填入输入框</button>
        <button
          v-if="voiceDraft && isGroup"
          type="button"
          :disabled="sending || contextActive"
          @click="emit('shareCaption', false)"
        >
          {{ sending ? "发送中…" : "分享字幕到讨论室" }}
        </button>
        <button type="button" @click="emit('stopVoice')">丢弃</button>
      </div>
    </div>
    <div v-if="contextActive" class="read-only">
      <AppIcon name="lock" :size="16" /> 返回最新消息后可继续发送
    </div>
    <div v-else-if="!canWrite" class="read-only">
      <AppIcon name="lock" :size="16" /> 已解除好友关系，此对话为只读
    </div>
    <div v-else class="composer">
      <textarea
        v-model="composer"
        rows="2"
        aria-label="输入消息"
        placeholder="写点什么，继续这段讨论…"
        @keydown.enter.exact.prevent="emit('send')"
      />
      <div v-if="mentionPickerOpen && isGroup" class="mention-picker" role="group" aria-label="选择要提醒的群成员">
        <button
          v-for="id in mentionIds"
          :key="id"
          type="button"
          class="text-button"
          @click="emit('mention', id)"
        >
          @{{ person(id).name }} · {{ id.replace("user-", "") }}
        </button>
        <span v-if="mentionIds.length === 0">暂无其他成员</span>
      </div>
      <div class="composer-tools">
        <div class="tool-left">
          <button
            class="tool-button"
            :class="{ recording: voiceActive }"
            :title="voiceActive ? '停止录音' : '本机语音转写（确认后发送）'"
            :aria-label="voiceActive ? '停止录音' : '开始本机语音转写'"
            :disabled="voiceBusy"
            @click="emit('toggleVoice')"
          >
            <AppIcon name="mic" :size="18" /></button
          ><button
            v-if="isGroup"
            class="tool-button caption-button"
            title="将输入框文字作为字幕分享给讨论室成员"
            aria-label="作为字幕分享"
            :disabled="!canSend"
            @click="emit('shareCaption', true)"
          >
            <AppIcon name="alignLeft" :size="18" /> 作为字幕发送</button
          ><button
            v-if="isGroup"
            class="tool-button"
            title="选择要提醒的成员"
            aria-label="提及成员"
            @click="mentionPickerOpen = !mentionPickerOpen"
          >
            <AppIcon name="at" :size="18" /></button
          ><span class="composer-tip">Enter 发送 · Shift + Enter 换行</span>
        </div>
        <button class="send-button" :disabled="!canSend" @click="emit('send')">
          发送 <AppIcon name="arrowUp" :size="16" />
        </button>
      </div>
    </div>
  </div>
</template>
