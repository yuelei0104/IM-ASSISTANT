import { nextTick, ref, type Ref } from "vue";
import {
  accountStateKey,
  clearSession,
  currentUser,
  logout,
  sessionToken,
  ApiError,
  type Account,
} from "./authApi";
import type { ContactProfile } from "./contactApi";
import { initialState, type DemoState } from "./demo";

export type ShellPage = "chat" | "contacts" | "requests" | "notices" | "tasks" | "settings";

/** The slice of each feature module that an account switch has to poke. */
type Modules = {
  agent: { reset: () => void };
  translation: { clear: () => void };
  voice: { stop: () => void };
  realtime: { start: () => void; stop: () => void };
  feed: { reset: () => void; refreshAll: () => void };
  outbox: { clearPending: () => void };
  contacts: { reset: () => void; refresh: () => unknown };
  summaries: { reset: () => void };
  tasks: { reset: () => void; refresh: () => unknown };
  notices: { reset: () => void; refresh: () => unknown };
  historySearch: { close: () => void };
  messageContext: { close: (showLatest?: boolean) => void };
};

type Deps = {
  account: Ref<Account | null>;
  data: Ref<DemoState>;
  knownPeers: Ref<ContactProfile[]>;
  activeId: Ref<string>;
  page: Ref<ShellPage>;
  mentionSelections: Ref<Record<number, string>>;
  auth: { reset: () => void; error: Ref<string> };
  modules: Modules;
  loadFocusWords: () => unknown;
  firstConversationId: () => string;
  scrollBottom: () => void;
  notify: (message: string) => void;
};

function load(account: Account | null): DemoState {
  if (!account) return initialState();
  try {
    const saved = localStorage.getItem(accountStateKey(account.id));
    if (saved) {
      const v = JSON.parse(saved);
      if (v.people && v.conversations && v.preferences) {
        // Legacy demo tasks are deliberately not imported as real server tasks.
        v.todos = [];
        return v;
      }
    }
  } catch {}
  return initialState();
}

/** Where the sidebar's peer cache for one account lives. */
export function peerCacheKey(id: number): string {
  return `${accountStateKey(id)}-chat-peers`;
}

function loadKnownPeers(account: Account): ContactProfile[] {
  try {
    const saved = JSON.parse(localStorage.getItem(peerCacheKey(account.id)) || "[]");
    if (!Array.isArray(saved)) return [];
    return saved.filter(
      (p) =>
        Number.isSafeInteger(p.id) &&
        p.id > 0 &&
        typeof p.nickname === "string" &&
        typeof p.handle === "string",
    );
  } catch {
    return [];
  }
}

/**
 * Sign-in, sign-out and session expiry. Every account switch starts from a blank
 * module state, so nothing from the previous user (transcripts, tasks, notices,
 * peer cache) can leak into the next one.
 */
export function useAccountSession(deps: Deps) {
  const restoring = ref(Boolean(sessionToken()));

  function apply(account: Account) {
    const m = deps.modules;
    m.agent.reset();
    m.translation.clear();
    m.voice.stop();
    m.realtime.stop();
    m.feed.reset();
    m.outbox.clearPending();
    m.contacts.reset();
    m.summaries.reset();
    m.tasks.reset();
    m.notices.reset();
    deps.mentionSelections.value = {};
    deps.knownPeers.value = loadKnownPeers(account);
    deps.activeId.value = "";
    deps.data.value = load(account);
    deps.data.value.preferences.focusWords = "";
    deps.data.value.displayName = account.name;
    deps.data.value.handle = account.handle;
    deps.data.value.people[0]!.name = account.name;
    deps.data.value.people[0]!.handle = account.handle;
    deps.data.value.people[0]!.initials = account.name[0]!;
    deps.account.value = account;
    deps.page.value = "chat";
    void m.contacts.refresh();
    m.feed.refreshAll();
    void m.notices.refresh();
    void deps.loadFocusWords();
    void m.tasks.refresh();
    m.realtime.start();
    nextTick(deps.scrollBottom);
  }

  /** Drops every trace of the current account without touching the server. */
  function resetClientState() {
    const m = deps.modules;
    m.agent.reset();
    m.voice.stop();
    m.translation.clear();
    m.historySearch.close();
    m.messageContext.close(false);
    m.realtime.stop();
    m.contacts.reset();
    m.outbox.clearPending();
    m.feed.reset();
    m.summaries.reset();
    m.tasks.reset();
    m.notices.reset();
    deps.knownPeers.value = [];
    deps.mentionSelections.value = {};
    deps.account.value = null;
    deps.page.value = "chat";
    deps.auth.reset();
  }

  async function signOut() {
    try {
      await logout();
    } catch (error) {
      // A token the server already forgot is the same outcome as a clean logout.
      if (error instanceof ApiError && error.status === 401) clearSession();
      else {
        deps.notify(error instanceof Error ? error.message : "退出失败，请稍后重试");
        return;
      }
    }
    resetClientState();
  }

  /** Session expiry observed anywhere in the app lands here exactly once. */
  function handleExpiry() {
    if (deps.account.value) resetClientState();
    restoring.value = false;
    deps.auth.error.value = "登录已过期，请重新登录";
  }

  /** Resume a stored token on load; a dead token falls back to the auth screen. */
  async function restore() {
    if (!sessionToken()) {
      restoring.value = false;
      return;
    }
    try {
      apply(await currentUser());
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) clearSession();
      deps.auth.error.value = error instanceof Error ? error.message : "无法恢复登录状态";
    } finally {
      restoring.value = false;
    }
  }

  /** Local demo data only: server tasks and transcripts are untouched. */
  function resetDemo() {
    if (!confirm("重置本地演示数据和偏好？服务端待办不会删除。")) return;
    deps.data.value = initialState();
    deps.activeId.value = deps.firstConversationId();
    deps.notify("本地演示数据已重置，服务端待办和聊天记录不受影响");
  }

  return { restoring, apply, signOut, resetClientState, handleExpiry, restore, resetDemo };
}
