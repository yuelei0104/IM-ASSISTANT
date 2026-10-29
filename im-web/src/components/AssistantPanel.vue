<script setup lang="ts">
import AppIcon from "./AppIcon.vue";
import { chinaDateTime } from "../chinaTime";
import type { useGroupAgent } from "../useGroupAgent";
import type { useGroupSummaries } from "../useGroupSummaries";

const props = defineProps<{
  open: boolean;
  /** The open conversation is a server group, which is what unlocks the agent and summary tools. */
  isGroup: boolean;
  agent: ReturnType<typeof useGroupAgent>;
  summaries: ReturnType<typeof useGroupSummaries>;
  /** Direct-message clues, already resolved to "name：text" so this panel stays presentational. */
  sources: Array<{ id: string; conversationId: string; label: string }>;
  focusCount: number;
  taskSaving: boolean;
}>();

const showSummary = defineModel<boolean>("showSummary", { required: true });
const range = defineModel<"recent" | "all">("range", { required: true });
const taskDraft = defineModel<string>("taskDraft", { required: true });

const emit = defineEmits<{
  close: [];
  addTask: [];
  openSource: [payload: { conversationId: string; messageId: string }];
  manageFocusWords: [];
}>();

const { question: agentQuestion, answer: agentAnswer, error: agentError,
  busy: agentBusy, ask: askAgent } = props.agent;
const { items: groupSummaries, evidenceFor: summaryEvidenceFor,
  evidence: summaryEvidence, evidenceBusy: summaryEvidenceBusy,
  evidenceError: summaryEvidenceError, busy: summaryBusy, error: summaryError,
  load: loadGroupSummaries, create: createGroupSummary,
  toggleEvidence: toggleSummaryEvidence } = props.summaries;
</script>

<template>
  <aside :class="['assistant-panel', { open }]">
    <div class="assistant-header">
      <div>
        <div class="assistant-brand">
          <span class="assistant-mark"><AppIcon name="sparkles" :size="17" /></span
          ><strong>接上讨论</strong><span class="beta">演示</span>
        </div>
        <p>从消息里理清思路，接住下一步。</p>
      </div>
      <button class="icon-button close-assistant" aria-label="关闭助手" @click="emit('close')">
        <AppIcon name="x" />
      </button>
    </div>
    <div class="assistant-scroll">
      <div class="assist-hero">
        <span class="assist-hero-icon"><AppIcon name="sparkles" :size="23" /></span>
        <p>让重要的事<br /><strong>自然浮现。</strong></p>
        <small>选择会话，整理讨论中的线索。</small>
      </div>
      <div class="assist-section">
        <div class="assist-label">
          <AppIcon name="alignLeft" :size="17" /><strong>对话摘要</strong>
        </div>
        <template v-if="isGroup">
          <div class="agent-ask">
            <div class="assist-label">
              <AppIcon name="sparkles" :size="17" /><strong>群聊 Agent 问答</strong>
            </div>
            <p class="assist-description">
              检索你有权查看的讨论室历史消息；本地模型生成回答，可核对引用。不会自动创建待办。
            </p>
            <label for="agent-question">向当前讨论室提问</label>
            <input
              id="agent-question"
              v-model="agentQuestion"
              class="agent-question"
              maxlength="300"
              placeholder="例如：下次会议要准备什么？"
              @keydown.enter.prevent="askAgent"
            />
            <button
              class="soft-button"
              type="button"
              :disabled="agentBusy || agentQuestion.trim().length < 2"
              @click="askAgent"
            >
              {{ agentBusy ? "检索并回答中…" : "提问" }}
            </button>
            <p v-if="agentError" class="error-text" role="alert">{{ agentError }}</p>
            <div v-if="agentAnswer" class="summary-card">
              <p class="group-summary-text">{{ agentAnswer.answer }}</p>
              <strong v-if="agentAnswer.sources.length" class="summary-label"
                >可核对的来源</strong
              >
              <div
                v-for="source in agentAnswer.sources"
                :key="source.id"
                class="summary-evidence-row"
              >
                <small>消息 {{ source.id }}</small><p>{{ source.body }}</p>
              </div>
            </div>
          </div>
          <p class="assist-description">
            只使用加入后可见的群消息，由本机模型生成；摘要仅对有权查看这些消息的群成员开放。
          </p>
          <button class="soft-button" type="button" :disabled="summaryBusy" @click="createGroupSummary">
            <AppIcon name="sparkles" :size="15" /> {{ summaryBusy ? "处理中…" : "生成群聊摘要" }}
          </button>
          <button class="text-button" type="button" :disabled="summaryBusy" @click="loadGroupSummaries">
            查看群内摘要
          </button>
          <p v-if="summaryError" class="error-text" role="alert">{{ summaryError }}</p>
          <div v-if="showSummary" class="summary-card">
            <p v-if="!groupSummaries.length">本群暂无你有权查看的摘要。</p>
            <article v-for="item in groupSummaries" :key="item.id" class="group-summary-item">
              <small
                >消息 {{ item.firstMessageId }}–{{ item.lastMessageId }} ·
                {{ chinaDateTime(item.createdAt) }}</small
              >
              <p class="group-summary-text">{{ item.content }}</p>
              <button
                type="button"
                class="text-button"
                :aria-expanded="summaryEvidenceFor === item.id"
                :aria-label="`核对摘要 ${item.id} 的原文`"
                @click="toggleSummaryEvidence(item.id)"
              >
                {{ summaryEvidenceFor === item.id ? "收起原文" : "核对原文" }}
              </button>
              <div v-if="summaryEvidenceFor === item.id" class="summary-evidence">
                <p v-if="summaryEvidenceBusy">正在加载原文…</p>
                <p v-else-if="summaryEvidenceError" role="alert">{{ summaryEvidenceError }}</p>
                <template v-else>
                  <p>生成时覆盖的可见消息（最多 30 条），请对照检查结论和待办。</p>
                  <div
                    v-for="source in summaryEvidence"
                    :key="source.id"
                    class="summary-evidence-row"
                  >
                    <small
                      >消息 {{ source.id }} · 用户 {{ source.senderId }} ·
                      {{ chinaDateTime(source.createdAt) }} ·
                      {{ source.kind === "CAPTION" ? "字幕" : "文字" }}</small
                    >
                    <p>{{ source.body }}</p>
                  </div>
                </template>
              </div>
            </article>
          </div>
        </template>
        <template v-else>
          <p class="assist-description">私聊仅展示本地演示消息线索；群聊摘要由本机模型生成。</p>
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
            <small>消息线索 · {{ range === "recent" ? "最近 5 条" : "全部消息" }}</small>
            <p v-if="!sources.length">当前范围暂无可整理的消息。</p>
            <template v-else
              ><p>以下是值得回看的发言，点击定位到原消息：</p>
              <button
                v-for="m in sources"
                :key="m.id"
                class="source-link"
                @click="emit('openSource', { conversationId: m.conversationId, messageId: m.id })"
              >
                {{ m.label }} <AppIcon name="arrowUpRight" :size="14" /></button
            ></template>
          </div>
        </template>
      </div>
      <div class="assist-section">
        <div class="assist-label">
          <AppIcon name="checkSquare" :size="17" /><strong>记下下一步</strong>
        </div>
        <p class="assist-description">新建待办需要你亲自确认。</p>
        <input
          v-model="taskDraft"
          class="task-draft"
          aria-label="待办标题"
          placeholder="例如：确认本周接口时间"
          maxlength="100"
          @keydown.enter="emit('addTask')"
        /><button class="soft-button" :disabled="!taskDraft.trim() || taskSaving" @click="emit('addTask')">
          <AppIcon name="plus" :size="15" /> 确认创建待办
        </button>
      </div>
      <div class="assist-section highlight-section">
        <div class="assist-label">
          <AppIcon name="target" :size="17" /><strong>关注词</strong>
        </div>
        <p>当前会话有 <strong>{{ focusCount }}</strong> 条消息提到关注词。</p>
        <button @click="emit('manageFocusWords')">
          管理关注词 <AppIcon name="arrowRight" :size="14" />
        </button>
      </div>
    </div>
    <div class="assistant-footer">
      <span class="status-dot" /> 群聊摘要仅群成员可见 · 群消息本机翻译需手动确认
    </div>
  </aside>
</template>
