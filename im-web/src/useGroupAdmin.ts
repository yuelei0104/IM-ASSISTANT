import { type Ref } from "vue";
import {
  createGroupChat,
  dissolveGroup,
  inviteGroupMembers,
  leaveGroup,
  removeGroupMember,
  transferGroupOwner,
  type GroupChat,
} from "./groupApi";
import { serverGroupId } from "./chatIds";
import { selfId, type Conversation } from "./demo";

type Deps = {
  activeId: () => string;
  active: () => Conversation | undefined;
  serverGroups: Ref<GroupChat[]>;
  /** Names picked in the invite dialog. */
  inviteIds: Ref<string[]>;
  groupName: Ref<string>;
  /** Shared busy flag: every room mutation locks the same buttons. */
  busy: Ref<boolean>;
  /** Room mutations report validation problems into the invite dialog. */
  error: Ref<string>;
  notify: (message: string) => void;
  openChat: (id: string) => void;
  closeModal: () => void;
  /** Drops the room from the feed after leaving or dissolving it. */
  forgetGroup: (groupId: number) => void;
  nameOf: (personId: string) => string;
};

/** `user-12` → 12. Returns 0 for anything that is not a positive safe integer. */
function memberId(id: string): number {
  const value = Number(id.slice(5));
  return Number.isSafeInteger(value) && value > 0 ? value : 0;
}

/**
 * The six owner-level room mutations. Each one keeps the local room list in step with
 * the server response so the header and drawer never show stale membership.
 */
export function useGroupAdmin(deps: Deps) {
  async function create() {
    const name = deps.groupName.value.trim();
    if (!name || deps.inviteIds.value.length > 20 || deps.busy.value) return;
    deps.busy.value = true;
    try {
      const created = await createGroupChat(name, deps.inviteIds.value.map(memberId));
      deps.serverGroups.value = [created, ...deps.serverGroups.value];
      deps.closeModal();
      deps.openChat(`server-group-${created.id}`);
    } catch (error) {
      deps.error.value = error instanceof Error ? error.message : "建群失败";
    } finally {
      deps.busy.value = false;
    }
  }

  async function addMembers() {
    const groupId = serverGroupId(deps.activeId());
    const invited = deps.inviteIds.value;
    const room = deps.active();
    if (
      !room ||
      !groupId ||
      room.ownerId !== selfId ||
      !invited.length ||
      invited.length > 20 ||
      room.memberIds.length + invited.length > 50
    ) {
      deps.notify("最多 50 人，每次邀请不超过 20 人");
      return;
    }
    if (deps.busy.value) return;
    deps.busy.value = true;
    try {
      const updated = await inviteGroupMembers(groupId, invited.map(memberId));
      deps.serverGroups.value = deps.serverGroups.value.map((g) =>
        g.id === groupId ? updated : g,
      );
      deps.closeModal();
      deps.notify("邀请已发送，新成员可以查看加入后的消息");
    } catch (error) {
      deps.error.value = error instanceof Error ? error.message : "邀请失败";
    } finally {
      deps.busy.value = false;
    }
  }

  async function leave() {
    const groupId = serverGroupId(deps.activeId());
    if (
      !groupId ||
      deps.active()?.ownerId === selfId ||
      deps.busy.value ||
      !confirm("确定退出讨论室？退出后无法查看原群的消息、点名通知和摘要。")
    )
      return;
    deps.busy.value = true;
    try {
      await leaveGroup(groupId);
      deps.forgetGroup(groupId);
      deps.notify("已退出讨论室");
    } catch (error) {
      deps.notify(error instanceof Error ? error.message : "退出失败");
    } finally {
      deps.busy.value = false;
    }
  }

  async function removeMember(personId: string) {
    const groupId = serverGroupId(deps.activeId());
    const target = memberId(personId);
    if (
      !groupId ||
      deps.active()?.ownerId !== selfId ||
      !target ||
      deps.busy.value ||
      !confirm(`确定将 ${deps.nameOf(personId)} 移出讨论室？对方将无法继续查看群内容。`)
    )
      return;
    deps.busy.value = true;
    try {
      await removeGroupMember(groupId, target);
      deps.serverGroups.value = deps.serverGroups.value.map((group) =>
        group.id === groupId
          ? { ...group, memberIds: group.memberIds.filter((id) => id !== target) }
          : group,
      );
      deps.notify("成员已移出讨论室");
    } catch (error) {
      deps.notify(error instanceof Error ? error.message : "移除失败");
    } finally {
      deps.busy.value = false;
    }
  }

  async function transferOwner(personId: string) {
    const groupId = serverGroupId(deps.activeId());
    const target = memberId(personId);
    if (
      !groupId ||
      deps.active()?.ownerId !== selfId ||
      !target ||
      deps.busy.value ||
      !confirm(`确定将讨论室群主转让给 ${deps.nameOf(personId)}？转让后你仍是群成员。`)
    )
      return;
    deps.busy.value = true;
    try {
      const updated = await transferGroupOwner(groupId, target);
      deps.serverGroups.value = deps.serverGroups.value.map((group) =>
        group.id === groupId ? updated : group,
      );
      deps.notify("群主已转让，你现在可以退出讨论室");
    } catch (error) {
      deps.notify(error instanceof Error ? error.message : "转让失败");
    } finally {
      deps.busy.value = false;
    }
  }

  async function dissolve() {
    const groupId = serverGroupId(deps.activeId());
    const room = deps.active();
    if (
      !groupId ||
      !room ||
      room.ownerId !== selfId ||
      deps.busy.value ||
      !confirm(
        `确定解散「${room.name}」？所有成员将失去访问权限，群消息和摘要会被永久删除。`,
      )
    )
      return;
    deps.busy.value = true;
    try {
      await dissolveGroup(groupId);
      deps.forgetGroup(groupId);
      deps.notify("讨论室已解散");
    } catch (error) {
      deps.notify(error instanceof Error ? error.message : "解散失败");
    } finally {
      deps.busy.value = false;
    }
  }

  return {
    busy: deps.busy,
    create,
    addMembers,
    leave,
    removeMember,
    transferOwner,
    dissolve,
  };
}
