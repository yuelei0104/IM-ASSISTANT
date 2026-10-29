<script setup lang="ts">
import { ref, type Ref } from "vue";
import AppIcon from "./AppIcon.vue";
import type { Message, Person } from "../demo";
import type { ContextMessage } from "../useMessageContext";

/**
 * The transcript is bound to so many independent pieces of shell state (pagination,
 * context browsing, translation preview, recall) that listing them as separate props
 * would be noise. One typed bag keeps the wiring checkable and the markup readable.
 */
export type TranscriptContext = {
  messages: Ref<Message[]>;
  chatBusy: Ref<boolean>;
  chatError: Ref<string>;
  canWrite: Ref<boolean>;
  isDirect: Ref<boolean>;
  isGroup: Ref<boolean>;

  contextActive: Ref<boolean>;
  contextMessages: Ref<ContextMessage[]>;
  contextTarget: Ref<ContextMessage | null>;
  contextBeforeId: Ref<number | null>;
  contextAfterId: Ref<number | null>;
  contextBusy: Ref<boolean>;
  contextError: Ref<string>;

  nextBeforeId: Ref<number | null>;
  groupNextBeforeId: Ref<number | null>;
  unreadMarker: Ref<string>;
  focusId: Ref<string>;

  showTranslations: Ref<boolean>;
  translationFor: Ref<string | null>;
  translationBusy: Ref<string | null>;
  translationError: Ref<string>;
  translationPreview: Ref<{ groupId: number; messageId: number; text: string; target: "en" | "zh" } | null>;

  recallingMessageId: Ref<string | null>;
  person: (id: string) => Person;

  canRecall: (m: Message) => boolean;
  onLoadOlderDirect: () => void;
  onLoadOlderGroup: () => void;
  onRetryChat: () => void;
  onLoadContext: (direction: "older" | "newer") => void;
  onOpenContext: (target: ContextMessage | null) => void;
  onRecall: (m: Message) => void;
  onStageTask: (m: Message) => void;
  onTranslate: (m: Message) => void;
  onUseTranslation: () => void;
  onClearTranslation: () => void;
};
const props = defineProps<{ ctx: TranscriptContext }>();
/** Two-way: the preview deliberately keeps whatever the user edited until they discard it. */
const translationDraft = defineModel<string>("translationDraft", { required: true });

const {
  messages, chatBusy, chatError, canWrite, isDirect, isGroup,
  contextActive, contextMessages, contextTarget, contextBeforeId, contextAfterId,
  contextBusy, contextError, nextBeforeId, groupNextBeforeId, unreadMarker, focusId,
  showTranslations, translationFor, translationBusy, translationError,
  translationPreview, recallingMessageId, person,
  canRecall, onLoadOlderDirect, onLoadOlderGroup, onRetryChat,
  onLoadContext, onOpenContext, onRecall, onStageTask, onTranslate,
  onUseTranslation, onClearTranslation,
} = props.ctx;

/** The shell owns every scroll computation, so it asks for the element through this ref. */
const scroller = ref<HTMLElement | null>(null);
defineExpose({ scroller });

</script>

<template>
  <div ref="scroller" class="message-scroll">
    <div class="messages-inner">
      <div v-if="contextError" class="error-text" role="alert">
        {{ contextError }}
        <button
          v-if="!contextMessages.length"
          class="text-button"
          type="button"
          @click="onOpenContext(contextTarget)"
        >
          重试
        </button>
      </div>
      <div v-if="chatError && !contextActive" class="error-text" role="alert">
        {{ chatError }}
        <button class="text-button" type="button" :disabled="chatBusy" @click="onRetryChat">重试</button>
      </div>
      <button
        v-if="contextActive && contextBeforeId"
        class="load-more"
        :disabled="contextBusy"
        @click="onLoadContext('older')"
      >
        {{ contextBusy ? "加载中…" : "查看更早的上下文" }}
      </button>
      <button
        v-if="!contextActive && isDirect && nextBeforeId"
        class="load-more"
        :disabled="chatBusy"
        @click="onLoadOlderDirect"
      >
        {{ chatBusy ? "加载中…" : "查看更早消息" }}
      </button>
      <button
        v-if="!contextActive && isGroup && groupNextBeforeId"
        class="load-more"
        :disabled="chatBusy"
        @click="onLoadOlderGroup"
      >
        {{ chatBusy ? "加载中…" : "查看更早消息" }}
      </button>
      <p v-if="contextActive && contextBusy && !contextMessages.length" role="status">正在读取消息上下文…</p>
      <p v-if="!contextActive && chatBusy && !messages.length" role="status">正在加载消息…</p>
      <p v-if="!contextActive && !chatBusy && !chatError && !messages.length" class="empty-list">
        还没有消息，发一条开始聊天吧
      </p>
      <div v-if="messages.length" class="day-divider">
        <span>{{ contextActive ? "搜索结果附近" : "今天 · 讨论继续" }}</span>
      </div>
      <template v-for="m in messages" :key="m.id"
        ><div v-if="!contextActive && unreadMarker === m.id" class="unread-divider">
          以下是未读消息
        </div>
        <div v-if="m.kind === 'system'" :id="`msg-${m.id}`" class="system-message">
          {{ m.text }}
        </div>
        <div
          v-else
          :id="`msg-${m.id}`"
          :class="['message-row', { mine: m.senderId === 'me', focused: focusId === m.id }]"
        >
          <span
            v-if="m.senderId !== 'me'"
            :class="['avatar', person(m.senderId).color, 'message-avatar']"
            >{{ person(m.senderId).initials }}</span
          >
          <div class="message-stack">
            <div class="message-meta">
              <span>{{ m.senderId === "me" ? "我" : person(m.senderId).name }}</span
              ><time>{{ m.time }}</time>
              <button
                v-if="canRecall(m)"
                type="button"
                class="text-button message-recall"
                :disabled="!!recallingMessageId"
                :aria-label="`撤回消息 ${m.time}`"
                @click="onRecall(m)"
              >
                撤回
              </button>
              <button
                v-if="isGroup && !m.recalled && m.id.startsWith('group-message-')"
                type="button"
                class="text-button message-recall"
                :aria-label="`从消息创建待办 ${m.time}`"
                @click="onStageTask(m)"
              >
                设为待办
              </button>
              <button
                v-if="isGroup && !m.recalled && m.id.startsWith('group-message-')"
                type="button"
                class="text-button message-recall"
                :disabled="!!translationBusy"
                :aria-label="`翻译消息 ${m.time}`"
                @click="onTranslate(m)"
              >
                {{ translationBusy === m.id ? "翻译中…" : "翻译" }}
              </button>
            </div>
            <div :class="['bubble', { recalled: m.recalled }]">
              <span v-if="m.kind === 'voice'" class="voice-label"
                ><AppIcon name="mic" :size="14" /> 语音转写</span
              >{{ m.text }}
            </div>
            <div v-if="m.translation && showTranslations" class="translation">
              <AppIcon name="globe" :size="13" /> 译文 · {{ m.translation }}
            </div>
            <div v-if="translationFor === m.id && translationError" class="error-text" role="alert">
              {{ translationError }}
            </div>
            <div v-if="translationFor === m.id && translationPreview" class="translation-preview">
              <strong
                >本机翻译预览 ·
                {{ translationPreview.target === "en" ? "English" : "简体中文" }}</strong
              >
              <p>请核对人名、数字和语义。预览仅自己可见，不会自动发送。</p>
              <textarea v-model="translationDraft" rows="3" maxlength="4000" aria-label="可编辑译文" />
              <div class="translation-preview-actions">
                <button
                  type="button"
                  :disabled="!translationDraft.trim() || contextActive || !canWrite"
                  @click="onUseTranslation"
                >
                  填入输入框
                </button>
                <button type="button" @click="onClearTranslation">丢弃</button>
              </div>
            </div>
            <div v-if="m.status" class="delivery">仅本地 · 未同步</div>
            <div v-else-if="m.readReceipt" class="delivery" aria-live="polite">{{ m.readReceipt }}</div>
          </div>
        </div></template
      >
      <button
        v-if="contextActive && contextAfterId"
        class="load-more"
        :disabled="contextBusy"
        @click="onLoadContext('newer')"
      >
        {{ contextBusy ? "加载中…" : "查看后续的上下文" }}
      </button>
    </div>
  </div>
</template>
