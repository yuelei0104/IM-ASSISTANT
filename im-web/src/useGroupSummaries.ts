import { ref, type Ref } from "vue";
import {
  generateGroupSummary, listGroupSummaries, listGroupSummarySources,
  type GroupSummary, type SummarySource,
} from "./groupApi";

/** Group summary state and source review stay scoped to one account and room. */
export function useGroupSummaries(
  groupId: () => number | null,
  accountId: () => number | null,
  showSummary: Ref<boolean>,
) {
  const items = ref<GroupSummary[]>([]);
  const evidenceFor = ref<number | null>(null);
  const evidence = ref<SummarySource[]>([]);
  const evidenceBusy = ref(false);
  const evidenceError = ref("");
  const busy = ref(false);
  const error = ref("");
  let sequence = 0;
  let evidenceSequence = 0;

  function clearEvidence() {
    evidenceSequence++;
    evidenceFor.value = null;
    evidence.value = [];
    evidenceBusy.value = false;
    evidenceError.value = "";
  }

  function invalidate() {
    sequence++;
    busy.value = false;
    items.value = [];
    clearEvidence();
  }

  function reset() {
    invalidate();
    error.value = "";
  }

  function current(room: number, account: number, requested: number): boolean {
    return requested === sequence && accountId() === account && groupId() === room;
  }

  async function load() {
    const room = groupId(), account = accountId();
    if (!room || !account || busy.value) return;
    const requested = ++sequence;
    busy.value = true;
    error.value = "";
    try {
      const result = await listGroupSummaries(room);
      if (!current(room, account, requested)) return;
      items.value = result;
      if (evidenceFor.value !== null && !result.some((item) => item.id === evidenceFor.value)) clearEvidence();
      showSummary.value = true;
    } catch (cause) {
      if (current(room, account, requested)) error.value = cause instanceof Error ? cause.message : "摘要加载失败";
    } finally {
      if (requested === sequence) busy.value = false;
    }
  }

  async function create() {
    const room = groupId(), account = accountId();
    if (!room || !account || busy.value) return;
    const requested = ++sequence;
    busy.value = true;
    error.value = "";
    try {
      const summary = await generateGroupSummary(room);
      if (!current(room, account, requested)) return;
      items.value = [summary, ...items.value.filter((item) => item.id !== summary.id)].slice(0, 20);
      showSummary.value = true;
    } catch (cause) {
      if (current(room, account, requested)) error.value = cause instanceof Error ? cause.message : "摘要生成失败";
    } finally {
      if (requested === sequence) busy.value = false;
    }
  }

  async function toggleEvidence(summaryId: number) {
    if (evidenceFor.value === summaryId) { clearEvidence(); return; }
    const room = groupId(), account = accountId();
    if (!room || !account) return;
    const requested = ++evidenceSequence;
    evidenceFor.value = summaryId;
    evidence.value = [];
    evidenceError.value = "";
    evidenceBusy.value = true;
    try {
      const result = await listGroupSummarySources(room, summaryId);
      if (requested !== evidenceSequence || accountId() !== account || groupId() !== room) return;
      evidence.value = result;
    } catch (cause) {
      if (requested === evidenceSequence && accountId() === account) {
        evidenceError.value = cause instanceof Error ? cause.message : "无法查看摘要依据";
        void load();
      }
    } finally {
      if (requested === evidenceSequence) evidenceBusy.value = false;
    }
  }

  return { items, evidenceFor, evidence, evidenceBusy, evidenceError, busy, error,
    clearEvidence, invalidate, reset, load, create, toggleEvidence };
}
