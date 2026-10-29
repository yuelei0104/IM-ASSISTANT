import { nextTick, ref, type Ref } from "vue";
import { recallDirectMessage, sendDirectMessage, type DirectMessage, type MessageRecall } from "./chatApi";
import { recallGroupMessage, sendGroupMessage, type GroupMessage } from "./groupApi";
import { directPeer, serverGroupId } from "./chatIds";
import { parseServerTime } from "./chinaTime";
import { selfId, type Message } from "./demo";

type Deps = {
  accountId: () => number | null;
  activeId: () => string;
  canWrite: () => boolean;
  contextActive: () => boolean;
  composer: Ref<string>;
  mentionSelections: Ref<Record<number, string>>;
  /** A transcript draft waiting for the reader to confirm it. */
  voiceDraft: Ref<string>;
  /** The thread's error line, owned by the feed so one banner covers load and send. */
  error: Ref<string>;
  mergeDirect: (messages: DirectMessage[]) => void;
  mergeGroup: (messages: GroupMessage[]) => void;
  applyDirectRecall: (recall: MessageRecall) => void;
  applyGroupRecall: (recall: MessageRecall) => void;
  /** A recall can invalidate the summary that is on screen. */
  refreshSummaries: () => void;
  stopVoice: (discard?: boolean) => void;
  scrollBottom: () => void;
  refreshHidden: () => void;
  notify: (message: string) => void;
};

/**
 * Outgoing messages and recall. A retry reuses the first client id for the identical
 * text so the server can deduplicate instead of posting the message twice.
 */
export function useChatSend(deps: Deps) {
  const sending = ref(false);
  const recallingId = ref<string | null>(null);
  let pendingSend: { peerId: number; text: string; clientMsgId: string } | null = null;
  let pendingGroupSend: {
    groupId: number; text: string; mentions: string; clientMsgId: string;
  } | null = null;
  let pendingCaption: { groupId: number; text: string; clientMsgId: string } | null = null;

  async function send() {
    const text = deps.composer.value.trim();
    if (!text || !deps.canWrite() || sending.value) return;
    const peerId = directPeer(deps.activeId());
    if (peerId) {
      const accountId = deps.accountId();
      if (!accountId) return;
      const clientMsgId = pendingSend?.peerId === peerId && pendingSend.text === text
        ? pendingSend.clientMsgId : crypto.randomUUID();
      pendingSend = { peerId, text, clientMsgId };
      sending.value = true;
      try {
        const saved = await sendDirectMessage(peerId, clientMsgId, text);
        if (deps.accountId() !== accountId || directPeer(deps.activeId()) !== peerId) return;
        deps.mergeDirect([saved]);
        deps.refreshHidden();
        deps.composer.value = "";
        pendingSend = null;
        deps.error.value = "";
        nextTick(deps.scrollBottom);
      } catch (cause) {
        deps.error.value = cause instanceof Error ? cause.message : "发送失败，请重试";
      } finally {
        sending.value = false;
      }
      return;
    }
    const groupId = serverGroupId(deps.activeId());
    if (!groupId) return;
    const accountId = deps.accountId();
    if (!accountId) return;
    const mentionedUserIds = Object.entries(deps.mentionSelections.value)
      .filter(([, token]) => text.includes(token))
      .map(([id]) => Number(id)).sort((a, b) => a - b);
    const mentions = mentionedUserIds.join(",");
    const clientMsgId = pendingGroupSend?.groupId === groupId && pendingGroupSend.text === text &&
        pendingGroupSend.mentions === mentions
      ? pendingGroupSend.clientMsgId : crypto.randomUUID();
    pendingGroupSend = { groupId, text, mentions, clientMsgId };
    sending.value = true;
    try {
      const saved = await sendGroupMessage(groupId, clientMsgId, text, mentionedUserIds);
      if (deps.accountId() !== accountId || serverGroupId(deps.activeId()) !== groupId) return;
      deps.mergeGroup([saved]);
      deps.composer.value = "";
      pendingGroupSend = null;
      deps.mentionSelections.value = {};
      deps.error.value = "";
      nextTick(deps.scrollBottom);
    } catch (cause) {
      deps.error.value = cause instanceof Error ? cause.message : "发送失败，请重试";
    } finally {
      sending.value = false;
    }
  }

  /** Speak-to-text is stored as a CAPTION message so the reader can still search it. */
  async function sendCaption(fromComposer = false) {
    const groupId = serverGroupId(deps.activeId());
    const accountId = deps.accountId();
    const text = (fromComposer ? deps.composer.value : deps.voiceDraft.value).trim();
    if (!groupId || !accountId || !text || !deps.canWrite() || sending.value ||
        deps.contextActive()) return;
    if (text.length > 4000) { deps.error.value = "字幕最多 4000 字"; return; }
    if (!fromComposer) deps.stopVoice(false);
    const clientMsgId = pendingCaption?.groupId === groupId && pendingCaption.text === text
      ? pendingCaption.clientMsgId : crypto.randomUUID();
    pendingCaption = { groupId, text, clientMsgId };
    sending.value = true;
    try {
      const saved = await sendGroupMessage(groupId, clientMsgId, text, [], "CAPTION");
      if (deps.accountId() !== accountId || serverGroupId(deps.activeId()) !== groupId) return;
      deps.mergeGroup([saved]);
      pendingCaption = null;
      if (fromComposer) {
        deps.composer.value = "";
        deps.mentionSelections.value = {};
      } else deps.voiceDraft.value = "";
      deps.error.value = "";
      nextTick(deps.scrollBottom);
    } catch (cause) {
      if (deps.accountId() === accountId)
        deps.notify(cause instanceof Error ? cause.message : "字幕发送失败，请重试");
    } finally {
      sending.value = false;
    }
  }

  function canRecall(message: Message) {
    return message.senderId === selfId && !message.recalled && !!message.createdAt &&
      /^(server|group)-message-[1-9]\d*$/.test(message.id) &&
      Date.now() - parseServerTime(message.createdAt).getTime() < 2 * 60_000;
  }

  async function recall(message: Message) {
    const peerId = directPeer(deps.activeId());
    const groupId = serverGroupId(deps.activeId());
    const messageId = Number(message.id.split("-").at(-1));
    if (!canRecall(message) || recallingId.value || !Number.isSafeInteger(messageId) ||
        (!peerId && !groupId) ||
        !window.confirm("确定撤回这条消息？所有设备会显示撤回标记。")) return;
    const accountId = deps.accountId();
    const conversationId = deps.activeId();
    recallingId.value = message.id;
    try {
      if (peerId) await recallDirectMessage(peerId, messageId);
      else await recallGroupMessage(groupId!, messageId);
      if (deps.accountId() !== accountId || deps.activeId() !== conversationId) return;
      const applied: MessageRecall = { id: 0, messageId, recalledAt: new Date().toISOString() };
      if (peerId) deps.applyDirectRecall(applied);
      else {
        deps.applyGroupRecall(applied);
        deps.refreshSummaries();
      }
      deps.notify("消息已撤回");
    } catch (cause) {
      deps.notify(cause instanceof Error ? cause.message : "撤回失败");
    } finally {
      recallingId.value = null;
    }
  }

  /** A conversation switch or a membership change discards the retry identities. */
  function clearPending(groupOnly = false) {
    if (!groupOnly) {
      pendingSend = null;
      pendingCaption = null;
    }
    pendingGroupSend = null;
  }

  return { sending, recallingId, send, sendCaption, recall, canRecall, clearPending };
}
