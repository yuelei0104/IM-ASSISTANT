import { ref, type Ref } from "vue";
import { translateGroupMessage } from "./groupApi";
import { serverGroupId } from "./chatIds";
import type { Message } from "./demo";

type Deps = {
  accountId: () => number | null;
  activeId: () => string;
  /** The reader's preferred language; changing it only affects the next request. */
  target: () => "en" | "zh";
  canWrite: () => boolean;
  contextActive: () => boolean;
  composer: Ref<string>;
  notify: (message: string) => void;
};

export type TranslationPreview = {
  groupId: number;
  messageId: number;
  text: string;
  target: "en" | "zh";
};

/**
 * A translation is a draft, never a sent message: the preview asks the server for one
 * body, then lets the reader merge it into the composer themselves.
 */
export function useGroupTranslation(deps: Deps) {
  const preview = ref<TranslationPreview | null>(null);
  const draft = ref("");
  const forId = ref<string | null>(null);
  const busy = ref<string | null>(null);
  const error = ref("");
  let sequence = 0;

  function clear() {
    sequence++;
    preview.value = null;
    draft.value = "";
    forId.value = null;
    busy.value = null;
    error.value = "";
  }

  async function request(message: Message) {
    const groupId = serverGroupId(deps.activeId());
    const match = /^group-message-([1-9]\d*)$/.exec(message.id);
    const messageId = match ? Number(match[1]) : 0;
    const accountId = deps.accountId();
    if (!groupId || !accountId || !Number.isSafeInteger(messageId) || !messageId ||
        message.recalled || busy.value) return;
    const target = deps.target();
    const requested = ++sequence;
    preview.value = null;
    draft.value = "";
    error.value = "";
    forId.value = message.id;
    busy.value = message.id;
    try {
      const result = await translateGroupMessage(groupId, messageId, target);
      if (requested !== sequence || deps.accountId() !== accountId ||
          serverGroupId(deps.activeId()) !== groupId) return;
      preview.value = { groupId, messageId, text: result.text, target };
      draft.value = result.text;
    } catch (cause) {
      if (requested === sequence && deps.accountId() === accountId)
        error.value = cause instanceof Error ? cause.message : "翻译失败";
    } finally {
      if (requested === sequence) busy.value = null;
    }
  }

  /** Move the draft into the composer without sending it. */
  function useDraft() {
    const current = preview.value;
    if (!current || deps.contextActive() || !deps.canWrite() ||
        serverGroupId(deps.activeId()) !== current.groupId || !draft.value.trim()) return;
    const combined = [deps.composer.value.trim(), draft.value.trim()].filter(Boolean).join("\n");
    if (combined.length > 4000) {
      deps.notify("输入框内容超过 4000 字，请先精简译文");
      return;
    }
    deps.composer.value = combined;
    clear();
    deps.notify("译文已填入输入框，请检查后自行发送");
  }

  /** A recall invalidates both a finished preview and one still in flight. */
  function onRecalled(messageId: number) {
    if (preview.value?.messageId === messageId || busy.value === `group-message-${messageId}`)
      clear();
  }

  return { preview, draft, forId, busy, error, request, useDraft, clear, onRecalled };
}
