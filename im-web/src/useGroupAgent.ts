import { ref } from "vue";
import { askGroupAgent, type GroupAgentAnswer } from "./groupApi";

/** Keep Agent state, stale-response checks and recall invalidation together. */
export function useGroupAgent(
  groupId: () => number | null,
  accountId: () => number | null,
  conversationId: () => string,
) {
  const question = ref("");
  const answer = ref<GroupAgentAnswer | null>(null);
  const error = ref("");
  const busy = ref(false);
  let sequence = 0;

  function reset() {
    sequence++;
    question.value = "";
    answer.value = null;
    error.value = "";
    busy.value = false;
  }

  function invalidateOnRecall() {
    if (!answer.value && !busy.value) return;
    sequence++;
    answer.value = null;
    busy.value = false;
    // The model saw more messages than it cited. Any active-group recall
    // can invalidate the answer, even when the recalled ID is not a source.
    error.value = "群消息已撤回，请重新提问";
  }

  async function ask() {
    const group = groupId();
    const account = accountId();
    const text = question.value.trim();
    if (!group || !account || busy.value) return;
    if (text.length < 2 || text.length > 300) {
      error.value = "问题应为 2 到 300 个字符";
      return;
    }
    const current = ++sequence;
    const conversation = conversationId();
    busy.value = true;
    answer.value = null;
    error.value = "";
    try {
      const result = await askGroupAgent(group, text);
      if (current === sequence && accountId() === account && conversationId() === conversation)
        answer.value = result;
    } catch (cause) {
      if (current === sequence && accountId() === account && conversationId() === conversation)
        error.value = cause instanceof Error ? cause.message : "Agent 问答失败";
    } finally {
      if (current === sequence) busy.value = false;
    }
  }

  return { question, answer, error, busy, ask, reset, invalidateOnRecall };
}
