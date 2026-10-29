<script setup lang="ts">
import AppIcon from "./AppIcon.vue";
import { chinaDate, chinaToday } from "../chinaTime";
import type { useTasks } from "../useTasks";

const props = defineProps<{ tasks: ReturnType<typeof useTasks> }>();

/** The draft, its due date and the inline editor belong to the composable but are edited here. */
const draft = defineModel<string>("draft", { required: true });
const draftDueDate = defineModel<string>("draftDueDate", { required: true });
const source = defineModel<{ sourceGroupId: number; sourceMessageId: number } | null>("source", { required: true });
const editingId = defineModel<number | null>("editingId", { required: true });
const editingTitle = defineModel<string>("editingTitle", { required: true });
const editingDueDate = defineModel<string>("editingDueDate", { required: true });

const { items, nextBeforeId, openCount, completedCount, loading, saving, error,
  refresh, add, toggle, startEdit, saveEdit, remove, openSource } = props.tasks;
</script>

<template>
  <div class="page-content">
    <div class="stat-row">
      <div class="stat-card">
        <span>进行中</span
        ><strong>{{ openCount.toString().padStart(2, "0") }}</strong
        ><small>一次做好一件事</small>
      </div>
      <div class="stat-card">
        <span>已完成</span
        ><strong>{{ completedCount.toString().padStart(2, "0") }}</strong
        ><small>每一步都值得记录</small>
      </div>
    </div>
    <div class="content-card">
      <div class="card-heading">
        <h2>我的待办</h2>
        <span>共 {{ openCount + completedCount }} 项</span>
      </div>
      <div class="add-task">
        <AppIcon name="plus" :size="18" /><input
          v-model="draft"
          maxlength="100"
          aria-label="新待办标题"
          placeholder="添加一项待办，按 Enter 确认"
          @keydown.enter="add"
        /><button :disabled="!draft.trim() || saving" @click="add">添加</button>
      </div>
      <p v-if="source" class="task-source-draft">
        来源：讨论室消息。请编辑标题并确认保存，仅自己可见。
        <button type="button" class="text-button" @click="source = null">取消关联来源</button>
      </p>
      <label class="task-date-field"
        >截止日期（可选）
        <input v-model="draftDueDate" type="date" aria-label="新待办截止日期" :disabled="saving" />
      </label>
      <template v-for="t in items" :key="t.id">
        <div :class="['todo-row', { done: t.completed }]">
          <label class="todo-label"
            ><input
              :checked="t.completed"
              :disabled="saving"
              @change="toggle(t)"
              type="checkbox"
              :aria-label="`完成 ${t.title}`"
            /><span class="custom-check"><AppIcon name="check" :size="13" /></span
            ><span>{{ t.title }}</span></label
          ><span v-if="t.dueDate" :class="['due', { 'task-overdue': !t.completed && t.dueDate < chinaToday() }]">
            截止 {{ t.dueDate }}<span v-if="!t.completed && t.dueDate < chinaToday()"> · 已逾期</span>
          </span
          ><span v-else class="due">创建 {{ chinaDate(t.createdAt) }}</span
          ><button
            type="button"
            class="text-button"
            :disabled="saving"
            :aria-label="`编辑 ${t.title}`"
            @click="startEdit(t)"
          >
            编辑</button
          ><button
            v-if="t.sourceGroupId && t.sourceMessageId"
            type="button"
            class="text-button"
            :aria-label="`查看 ${t.title} 的来源消息`"
            @click="openSource(t)"
          >
            查看来源</button
          ><button class="icon-button subdued" :aria-label="`删除 ${t.title}`" :disabled="saving" @click="remove(t)">
            <AppIcon name="trash" :size="16" />
          </button>
        </div>
        <div v-if="editingId === t.id" class="task-edit-panel">
          <label>标题<input v-model="editingTitle" maxlength="100" aria-label="编辑待办标题" /></label>
          <label>截止日期<input v-model="editingDueDate" type="date" aria-label="编辑待办截止日期" /></label>
          <button type="button" :disabled="!editingTitle.trim() || saving" @click="saveEdit">保存</button>
          <button type="button" :disabled="saving" @click="editingId = null">取消</button>
        </div>
      </template>
      <p v-if="error" class="error-text" role="alert">{{ error }}</p>
      <button v-if="error && !loading" class="text-button" @click="refresh()">重试加载</button>
      <div v-if="loading && !items.length" class="empty-state">正在加载待办…</div>
      <div v-else-if="!items.length && !error" class="empty-state">一切都安排好了</div>
      <button v-if="nextBeforeId" class="text-button" :disabled="loading || saving" @click="refresh(nextBeforeId)">
        加载更早待办
      </button>
    </div>
  </div>
</template>
