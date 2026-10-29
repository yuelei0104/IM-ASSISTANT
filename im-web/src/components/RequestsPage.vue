<script setup lang="ts">
import AppIcon from "./AppIcon.vue";
import { chinaDateTime } from "../chinaTime";
import type { ContactRequest } from "../contactApi";
import type { useContacts } from "../useContacts";

const props = defineProps<{
  contacts: ReturnType<typeof useContacts>;
  received: ContactRequest[];
  sent: ContactRequest[];
}>();

const { error: contactsError, busy: contactsBusy, requestsCursor: requestsNextCursor,
  refresh: refreshContacts, loadMoreRequests, resolve: resolveRequest } = props.contacts;

const settledLabel: Record<string, string> = {
  ACCEPTED: "已同意",
  REJECTED: "已拒绝",
};

function receivedLabel(state: ContactRequest["state"]) {
  return state === "PENDING" ? "等待你处理" : (settledLabel[state] ?? "已撤回");
}

function sentLabel(state: ContactRequest["state"]) {
  return state === "PENDING" ? "等待回应" : (settledLabel[state] ?? "已撤回");
}
</script>

<template>
  <div class="page-content">
    <p v-if="contactsError" class="error-text" role="alert">{{ contactsError }}</p>
    <p v-if="contactsBusy" role="status">正在加载申请…</p>
    <div class="content-card">
      <div class="card-heading">
        <h2>收到的申请</h2>
        <span>{{ received.filter((r) => r.state === "PENDING").length }} 条待处理</span>
        <button class="text-button" type="button" :disabled="contactsBusy" @click="refreshContacts">
          刷新
        </button>
      </div>
      <div v-if="!received.length && !contactsBusy" class="empty-state">
        <AppIcon name="inbox" :size="26" /><strong>暂时没有新申请</strong>
        <p>新的好友邀请会出现在这里。</p>
      </div>
      <div v-for="r in received" :key="r.id" class="request-row">
        <span class="avatar violet">{{ r.peerNickname[0] }}</span>
        <div class="request-body">
          <strong>{{ r.peerNickname }} <span>@{{ r.peerHandle }}</span></strong>
          <p>{{ r.note }}</p>
          <small>{{ chinaDateTime(r.createdAt) }} · {{ receivedLabel(r.state) }}</small>
        </div>
        <div v-if="r.state === 'PENDING'" class="row-actions">
          <button class="outline-button" @click="resolveRequest(r.id, 'reject')">拒绝</button
          ><button class="primary-button compact" @click="resolveRequest(r.id, 'accept')">同意</button>
        </div>
      </div>
    </div>
    <div class="content-card">
      <div class="card-heading">
        <h2>我发出的申请</h2>
        <span>{{ sent.filter((r) => r.state === "PENDING").length }} 条待回应</span>
      </div>
      <div v-if="!sent.length && !contactsBusy" class="empty-state mini">
        还没有发出的申请。通过唯一账号搜索新朋友吧。
      </div>
      <div v-for="r in sent" :key="r.id" class="request-row">
        <span class="avatar violet">{{ r.peerNickname[0] }}</span>
        <div class="request-body">
          <strong>{{ r.peerNickname }} <span>@{{ r.peerHandle }}</span></strong>
          <p>{{ r.note }}</p>
          <small>{{ chinaDateTime(r.createdAt) }} · {{ sentLabel(r.state) }}</small>
        </div>
        <button v-if="r.state === 'PENDING'" class="text-button" @click="resolveRequest(r.id, 'withdraw')">
          撤回
        </button>
      </div>
    </div>
    <button v-if="requestsNextCursor" class="text-button" :disabled="contactsBusy" @click="loadMoreRequests">
      加载更早申请
    </button>
  </div>
</template>
