import { connectChatSocket } from "./chatApi";
import { directPeer, serverGroupId } from "./chatIds";

type Deps = {
  accountId: () => number | null;
  activeId: () => string;
  page: () => string;
  refreshUnread: () => void;
  refreshHidden: () => void;
  refreshGroups: () => Promise<void>;
  refreshNotices: () => void;
  reconcileMembership: (groupId: number) => Promise<void>;
  requestDirect: () => void;
  requestGroup: () => void;
  requestReceipt: (peerId: number) => void;
  /** Recording must stop as soon as the tab is hidden. */
  pauseVoice: () => void;
};

/** Pub/Sub is not durable, so the socket is a hint layer over a slow recovery poll. */
const POLL_MS = 5000;
const HEALTHY_WINDOW_MS = 30_000;

/**
 * One event pipeline for the whole app: the socket pushes a hint, the poll covers lost
 * hints, and both funnel into the same refresh fan-out.
 */
export function useRealtime(deps: Deps) {
  let socket: ReturnType<typeof connectChatSocket> | null = null;
  let lastHealthyPoll = 0;

  function onChatEvent(kind?: "direct" | "group" | "notice" | "receipt", membershipGroupId?: number) {
    if (!deps.accountId()) return;
    if (kind === "receipt") {
      const peerId = directPeer(deps.activeId());
      if (peerId && peerId === membershipGroupId) deps.requestReceipt(peerId);
      return;
    }
    if (!kind || kind === "direct") {
      deps.refreshUnread();
      deps.refreshHidden();
    }
    if (!kind || kind === "group") {
      const refreshed = deps.refreshGroups();
      if (membershipGroupId) void refreshed.then(() => deps.reconcileMembership(membershipGroupId));
    }
    if (!kind || kind === "notice") deps.refreshNotices();
    if ((!kind || kind === "direct") && deps.page() === "chat" && directPeer(deps.activeId()))
      deps.requestDirect();
    if ((!kind || kind === "group") && !membershipGroupId && deps.page() === "chat" &&
        serverGroupId(deps.activeId()))
      deps.requestGroup();
  }

  function start() {
    socket = connectChatSocket(onChatEvent);
  }

  function stop() {
    socket?.close();
    socket = null;
  }

  function onVisibility() {
    if (document.hidden) {
      deps.pauseVoice();
      return;
    }
    if (!deps.accountId()) return;
    deps.refreshUnread();
    deps.refreshHidden();
    void deps.refreshGroups();
    deps.refreshNotices();
    if (deps.page() !== "chat") return;
    if (directPeer(deps.activeId())) deps.requestDirect();
    if (serverGroupId(deps.activeId())) deps.requestGroup();
  }

  const poll = setInterval(() => {
    if (!deps.accountId() || document.hidden) return;
    // Keep periodic recovery even when the socket is healthy (Pub/Sub is not durable).
    if (socket?.connected() && Date.now() - lastHealthyPoll < HEALTHY_WINDOW_MS) return;
    lastHealthyPoll = Date.now();
    onChatEvent();
  }, POLL_MS);
  // Browsers return a number; Node returns a handle that would keep a test run alive.
  (poll as unknown as { unref?: () => void }).unref?.();
  document.addEventListener("visibilitychange", onVisibility);

  function dispose() {
    stop();
    clearInterval(poll);
    document.removeEventListener("visibilitychange", onVisibility);
  }

  return { start, stop, dispose, refresh: onChatEvent };
}
