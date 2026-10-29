import { ref } from "vue";
import { ApiError } from "./authApi";
import { normalizeContact, validContact } from "./authValidation";
import {
  listFriends, listRequests, inviteFriend, resolveFriendRequest, deleteFriend,
  type ContactProfile, type ContactRequest,
} from "./contactApi";

type Deps = {
  accountId: () => number | null;
  /** Fold freshly loaded friends into the peer cache used to render conversations. */
  rememberPeers: (profiles: ContactProfile[]) => void;
  /** Leave the invite dialog and show the requests page. */
  showRequests: () => void;
  notify: (message: string) => void;
};

/** Friends, incoming/outgoing requests and the invite dialog's own form state. */
export function useContacts(deps: Deps) {
  const items = ref<ContactProfile[]>([]);
  const requests = ref<ContactRequest[]>([]);
  const friendsCursor = ref<number | null>(null);
  const requestsCursor = ref<number | null>(null);
  const busy = ref(false);
  const error = ref("");
  const searchType = ref<"phone" | "handle">("phone");
  const query = ref("");
  const note = ref("");
  const inviteBusy = ref(false);
  const inviteError = ref("");
  let sequence = 0;

  function current(account: number, requested: number): boolean {
    return requested === sequence && deps.accountId() === account;
  }

  function remember(profile: ContactProfile) {
    if (!items.value.some((item) => item.id === profile.id))
      items.value = [...items.value, profile];
  }

  async function refresh() {
    const account = deps.accountId();
    if (!account) return;
    const requested = ++sequence;
    busy.value = true;
    error.value = "";
    try {
      const [profiles, incoming] = await Promise.all([listFriends(), listRequests()]);
      if (!current(account, requested)) return;
      items.value = profiles.items;
      friendsCursor.value = profiles.nextCursor;
      requestsCursor.value = incoming.nextCursor;
      deps.rememberPeers(profiles.items);
      requests.value = incoming.items;
    } catch (cause) {
      if (!current(account, requested)) return;
      // A 401 is already a global teardown in the transport, so only report real errors.
      if (!(cause instanceof ApiError && cause.status === 401))
        error.value = cause instanceof Error ? cause.message : "无法加载好友";
    } finally {
      if (requested === sequence) busy.value = false;
    }
  }

  async function loadMoreFriends() {
    const cursor = friendsCursor.value;
    const account = deps.accountId();
    if (!cursor || !account || busy.value) return;
    const requested = sequence;
    busy.value = true;
    try {
      const page = await listFriends(cursor);
      if (!current(account, requested)) return;
      const merged = new Map(items.value.map((profile) => [profile.id, profile]));
      for (const profile of page.items) merged.set(profile.id, profile);
      items.value = [...merged.values()];
      friendsCursor.value = page.nextCursor;
      deps.rememberPeers(page.items);
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : "加载好友失败";
    } finally {
      if (requested === sequence) busy.value = false;
    }
  }

  async function loadMoreRequests() {
    const cursor = requestsCursor.value;
    const account = deps.accountId();
    if (!cursor || !account || busy.value) return;
    const requested = sequence;
    busy.value = true;
    try {
      const page = await listRequests(cursor);
      if (!current(account, requested)) return;
      requests.value = [...requests.value, ...page.items];
      requestsCursor.value = page.nextCursor;
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : "加载申请失败";
    } finally {
      if (requested === sequence) busy.value = false;
    }
  }

  /** Send an invite from the dialog's current field values. */
  async function invite() {
    if (inviteBusy.value) return;
    const type = searchType.value;
    const value = type === "phone"
      ? normalizeContact("phone", query.value)
      : query.value.trim().replace(/^@/, "").toLowerCase();
    if (type === "phone" ? !validContact("phone", value) : !/^[a-z0-9_]{3,20}$/.test(value)) {
      inviteError.value = type === "phone"
        ? "请输入正确的中国大陆手机号"
        : "请输入 3–20 位字母、数字或下划线账号";
      return;
    }
    inviteBusy.value = true;
    inviteError.value = "";
    try {
      await inviteFriend(type === "phone" ? { phone: value } : { handle: value }, note.value.trim());
      deps.showRequests();
      await refresh();
      deps.notify("如果账号存在且可接收申请，对方会收到邀请");
    } catch (cause) {
      inviteError.value = cause instanceof Error ? cause.message : "发送失败";
    } finally {
      inviteBusy.value = false;
    }
  }

  async function resolve(id: number, action: "accept" | "reject" | "withdraw") {
    try {
      await resolveFriendRequest(id, action);
      await refresh();
      deps.notify("申请状态已更新");
    } catch (cause) {
      deps.notify(cause instanceof Error ? cause.message : "处理失败");
      await refresh();
    }
  }

  /** `friend` keeps the demo `server-<id>` form used by conversations and templates. */
  async function remove(friend: { id: string; name: string }) {
    const match = /^server-([1-9]\d*)$/.exec(friend.id);
    if (!match) {
      deps.notify("无法识别该好友");
      return;
    }
    if (!confirm(`移除 ${friend.name}？过往私聊仍可查看。`)) return;
    try {
      await deleteFriend(Number(match[1]));
      await refresh();
      deps.notify("已移除好友");
    } catch (cause) {
      deps.notify(cause instanceof Error ? cause.message : "移除失败");
    }
  }

  /** Clear every binding and discard in-flight responses. Form drafts are kept. */
  function reset() {
    sequence++;
    items.value = [];
    requests.value = [];
    friendsCursor.value = null;
    requestsCursor.value = null;
    busy.value = false;
    error.value = "";
    inviteBusy.value = false;
    inviteError.value = "";
    searchType.value = "phone";
  }

  return {
    items, requests, friendsCursor, requestsCursor, busy, error,
    searchType, query, note, inviteBusy, inviteError,
    refresh, loadMoreFriends, loadMoreRequests, invite, resolve, remove, remember, reset,
  };
}
