package com.yuelei.imchat.service;

import com.yuelei.imchat.dao.GroupDao;
import com.yuelei.imchat.dao.GroupSummaryDao;
import com.yuelei.imchat.dao.NoticeDao;
import com.yuelei.imchat.dto.GroupDtos;
import com.yuelei.imchat.exception.ChatException;
import com.yuelei.imchat.model.GroupChat;
import com.yuelei.imchat.model.GroupMessage;
import com.yuelei.imchat.model.MessageRecall;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

@Service
public class GroupService {
    private final GroupDao dao;
    private final UserAccessClient access;
    private final ApplicationEventPublisher events;
    private final NoticeDao notices;
    private final GroupSummaryDao summaries;

    public GroupService(GroupDao dao, UserAccessClient access, ApplicationEventPublisher events,
                        NoticeDao notices, GroupSummaryDao summaries) {
        this.dao = dao;
        this.access = access;
        this.events = events;
        this.notices = notices;
        this.summaries = summaries;
    }

    @Transactional
    public GroupChat create(String authorization, GroupDtos.Create input) {
        long owner = access.requireUser(authorization);
        String name = input.name().trim();
        if (name.isEmpty() || name.length() > 30)
            throw new ChatException(HttpStatus.BAD_REQUEST, "讨论室名称无效");
        List<Long> invitees = validateFriends(authorization, owner, input.friendIds());
        var group = new GroupDao.NewGroup(name, owner);
        dao.insertGroup(group);
        long id = group.getId();
        dao.addMember(id, owner, 0);
        for (long userId : invitees) dao.addMember(id, userId, 0);
        events.publishEvent(new GroupEvent(id, 0, List.copyOf(invitees)));
        return detail(id);
    }

    public GroupDtos.Groups groups(String authorization, Long beforeId, int limit) {
        long userId = access.requireUser(authorization);
        if (beforeId != null && beforeId <= 0 || limit < 1 || limit > 50)
            throw new ChatException(HttpStatus.BAD_REQUEST, "分页参数无效");
        var rows = dao.groups(userId, beforeId, limit + 1);
        boolean older = rows.size() > limit;
        var selected = rows.subList(0, Math.min(limit, rows.size()));
        List<GroupChat> items = selected.stream()
                .map(row -> new GroupChat(row.id(), row.name(), row.ownerId(),
                        dao.memberIds(row.id()), row.unreadCount())).toList();
        return new GroupDtos.Groups(items, older ? items.get(items.size() - 1).id() : null);
    }

    public GroupChat detail(String authorization, long groupId) {
        long userId = access.requireUser(authorization);
        requireMember(groupId, userId);
        return detail(groupId);
    }

    private GroupChat detail(long groupId) {
        var row = dao.group(groupId);
        if (row == null) throw new ChatException(HttpStatus.NOT_FOUND, "讨论室不存在");
        return new GroupChat(groupId, row.name(), row.ownerId(), dao.memberIds(groupId), 0);
    }

    @Transactional
    public GroupChat invite(String authorization, long groupId, GroupDtos.Invite input) {
        long owner = access.requireUser(authorization);
        var row = dao.group(groupId);
        if (row == null) throw new ChatException(HttpStatus.NOT_FOUND, "讨论室不存在");
        if (row.ownerId() != owner) throw new ChatException(HttpStatus.FORBIDDEN, "只有群主可以邀请好友");
        List<Long> invitees = validateFriends(authorization, owner, input.friendIds());
        var locked = dao.lockGroup(groupId); // Serialize invitations with sends and owner changes.
        if (locked == null) throw new ChatException(HttpStatus.NOT_FOUND, "讨论室不存在");
        if (locked.ownerId() != owner) throw new ChatException(HttpStatus.FORBIDDEN, "群主已变更");
        var existing = new HashSet<>(dao.memberIds(groupId));
        if (existing.size() + invitees.size() > 50 || invitees.stream().anyMatch(existing::contains))
            throw new ChatException(HttpStatus.CONFLICT, "成员已加入或讨论室人数超过 50");
        long cursor = dao.latestId(groupId);
        for (long id : invitees) dao.addMember(groupId, id, cursor);
        events.publishEvent(new GroupEvent(groupId, 0, dao.memberIds(groupId)));
        return detail(groupId);
    }

    @Transactional
    public void leave(String authorization, long groupId) {
        long userId = access.requireUser(authorization);
        var group = lockedGroup(groupId);
        if (group.ownerId() == userId)
            throw new ChatException(HttpStatus.CONFLICT, "群主暂不能退出讨论室");
        removeCurrentMember(groupId, userId);
    }

    @Transactional
    public void remove(String authorization, long groupId, long memberId) {
        long userId = access.requireUser(authorization);
        var group = lockedGroup(groupId);
        if (group.ownerId() != userId)
            throw new ChatException(HttpStatus.FORBIDDEN, "只有群主可以移除成员");
        if (memberId <= 0 || memberId == userId)
            throw new ChatException(HttpStatus.BAD_REQUEST, "不能移除群主");
        removeCurrentMember(groupId, memberId);
    }

    @Transactional
    public GroupChat transferOwner(String authorization, long groupId, long memberId) {
        long userId = access.requireUser(authorization);
        var group = lockedGroup(groupId);
        if (group.ownerId() != userId)
            throw new ChatException(HttpStatus.FORBIDDEN, "只有群主可以转让讨论室");
        if (memberId <= 0 || memberId == userId || dao.membership(groupId, memberId) == null)
            throw new ChatException(HttpStatus.BAD_REQUEST, "只能转让给当前讨论室的其他成员");
        if (dao.transferOwner(groupId, memberId) != 1)
            throw new ChatException(HttpStatus.CONFLICT, "群主转让失败");
        events.publishEvent(new GroupEvent(groupId, 0, dao.memberIds(groupId)));
        return detail(groupId);
    }

    @Transactional
    public void dissolve(String authorization, long groupId) {
        long userId = access.requireUser(authorization);
        var group = lockedGroup(groupId);
        if (group.ownerId() != userId)
            throw new ChatException(HttpStatus.FORBIDDEN, "只有群主可以解散讨论室");
        var recipients = List.copyOf(dao.memberIds(groupId));
        // Child rows must be removed before the group because the schema has foreign keys.
        notices.deleteForGroup(groupId);
        summaries.deleteForGroup(groupId);
        dao.deleteRecalls(groupId);
        dao.deleteMessages(groupId);
        dao.deleteMembers(groupId);
        if (dao.deleteGroup(groupId) != 1)
            throw new ChatException(HttpStatus.CONFLICT, "讨论室解散失败");
        events.publishEvent(new GroupEvent(groupId, 0, recipients));
    }

    private GroupDao.GroupRow lockedGroup(long groupId) {
        if (groupId <= 0) throw new ChatException(HttpStatus.BAD_REQUEST, "讨论室编号无效");
        var group = dao.lockGroup(groupId);
        if (group == null) throw new ChatException(HttpStatus.NOT_FOUND, "讨论室不存在");
        return group;
    }

    private void removeCurrentMember(long groupId, long memberId) {
        if (dao.removeMember(groupId, memberId) != 1)
            throw new ChatException(HttpStatus.CONFLICT, "成员已退出讨论室");
        var recipients = new ArrayList<>(dao.memberIds(groupId));
        recipients.add(memberId); // Let the removed member clear this group's cached content.
        events.publishEvent(new GroupEvent(groupId, 0, List.copyOf(recipients)));
    }

    @Transactional
    public GroupMessage send(String authorization, long groupId, GroupDtos.SendGroup input) {
        long sender = access.requireUser(authorization);
        if (dao.lockGroup(groupId) == null) throw new ChatException(HttpStatus.NOT_FOUND, "讨论室不存在");
        requireMember(groupId, sender);
        String body = input.body().trim();
        if (body.isEmpty()) throw new ChatException(HttpStatus.BAD_REQUEST, "消息不能为空");
        String kind = input.kind() == null ? "TEXT" : input.kind();
        if (!kind.equals("TEXT") && !kind.equals("CAPTION"))
            throw new ChatException(HttpStatus.BAD_REQUEST, "消息类型无效");
        List<Long> targets = input.mentionedUserIds();
        if (targets == null || targets.size() > 10
                || targets.stream().anyMatch(id -> id == null || id <= 0 || id == sender)
                || new HashSet<>(targets).size() != targets.size()
                || !new HashSet<>(dao.memberIds(groupId)).containsAll(targets))
            throw new ChatException(HttpStatus.BAD_REQUEST, "只能提醒当前讨论室中的其他成员，最多 10 人");
        if (kind.equals("CAPTION") && !targets.isEmpty())
            throw new ChatException(HttpStatus.BAD_REQUEST, "字幕消息不能附带点名提醒");
        GroupMessage prior = dao.byClientId(sender, input.clientMsgId());
        if (prior != null) return existing(prior, groupId, body, kind, targets);
        dao.insertMessage(groupId, sender, input.clientMsgId(), body, kind);
        GroupMessage saved = dao.byClientId(sender, input.clientMsgId());
        if (saved == null) throw new ChatException(HttpStatus.INTERNAL_SERVER_ERROR, "消息保存失败");
        if (saved.groupId() != groupId || !saved.body().equals(body) || !saved.kind().equals(kind))
            throw new ChatException(HttpStatus.CONFLICT, "消息编号已用于另一条消息");
        for (long recipient : targets) notices.add(groupId, saved.id(), sender, recipient);
        events.publishEvent(new GroupEvent(groupId, saved.id(), dao.memberIds(groupId)));
        if (!targets.isEmpty()) events.publishEvent(new MentionEvent(List.copyOf(targets)));
        return saved;
    }

    private GroupMessage existing(GroupMessage prior, long groupId, String body, String kind, List<Long> targets) {
        if (prior.recalledAt() != null || prior.groupId() != groupId || !prior.body().equals(body) || !prior.kind().equals(kind)
                || !notices.recipients(prior.id()).equals(targets.stream().sorted().toList()))
            throw new ChatException(HttpStatus.CONFLICT, "消息编号已用于另一条消息");
        return prior;
    }

    @Transactional
    public void recall(String authorization, long groupId, long messageId) {
        long userId = access.requireUser(authorization);
        if (messageId <= 0) throw new ChatException(HttpStatus.BAD_REQUEST, "消息编号无效");
        if (dao.lockGroup(groupId) == null) throw new ChatException(HttpStatus.NOT_FOUND, "讨论室不存在");
        var membership = requireMember(groupId, userId);
        GroupMessage message = dao.messageById(groupId, messageId, membership.joinedAfterId());
        if (message == null || message.senderId() != userId)
            throw new ChatException(HttpStatus.NOT_FOUND, "只能撤回自己发送的可见消息");
        if (message.recalledAt() != null) return;
        if (dao.recall(groupId, messageId, userId) != 1)
            throw new ChatException(HttpStatus.CONFLICT, "只能在发送后两分钟内撤回");
        dao.addRecall(groupId, messageId);
        var mentioned = notices.recipients(messageId);
        notices.deleteForMessage(messageId);
        summaries.deleteContaining(groupId, messageId);
        events.publishEvent(new GroupEvent(groupId, messageId, dao.memberIds(groupId)));
        if (!mentioned.isEmpty()) events.publishEvent(new MentionEvent(mentioned));
    }

    public GroupDtos.RecallPage recalls(String authorization, long groupId, long afterId, int limit) {
        long userId = access.requireUser(authorization);
        if (afterId < 0 || limit < 1 || limit > 50)
            throw new ChatException(HttpStatus.BAD_REQUEST, "撤回游标无效");
        var membership = requireMember(groupId, userId);
        List<MessageRecall> rows = dao.recalls(groupId, membership.joinedAfterId(), afterId, limit + 1);
        List<MessageRecall> page = rows.subList(0, Math.min(rows.size(), limit));
        return new GroupDtos.RecallPage(page, rows.size() > limit ? page.get(page.size() - 1).id() : null);
    }

    public GroupDtos.Page history(String authorization, long groupId, Long beforeId, int limit) {
        long user = access.requireUser(authorization);
        if (beforeId != null && beforeId <= 0 || limit < 1 || limit > 50)
            throw new ChatException(HttpStatus.BAD_REQUEST, "分页参数无效");
        var membership = requireMember(groupId, user);
        List<GroupMessage> descending = dao.history(groupId, membership.joinedAfterId(), beforeId, limit + 1);
        boolean older = descending.size() > limit;
        List<GroupMessage> page = new ArrayList<>(descending.subList(0, Math.min(limit, descending.size())));
        Long next = older ? page.get(page.size() - 1).id() : null;
        Collections.reverse(page);
        return new GroupDtos.Page(page, next);
    }

    public GroupDtos.SyncPage sync(String authorization, long groupId, long afterId, int limit) {
        long user = access.requireUser(authorization);
        if (afterId < 0 || limit < 1 || limit > 50)
            throw new ChatException(HttpStatus.BAD_REQUEST, "增量游标无效");
        var membership = requireMember(groupId, user);
        List<GroupMessage> rows = dao.after(groupId, membership.joinedAfterId(), afterId, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<GroupMessage> page = rows.subList(0, Math.min(limit, rows.size()));
        return new GroupDtos.SyncPage(page, hasMore ? page.get(page.size() - 1).id() : null);
    }

    public GroupDtos.Page search(String authorization, long groupId, String query, Long beforeId, int limit) {
        long user = access.requireUser(authorization);
        if (beforeId != null && beforeId <= 0 || limit < 1 || limit > 50)
            throw new ChatException(HttpStatus.BAD_REQUEST, "分页参数无效");
        var membership = requireMember(groupId, user);
        String pattern = SearchTerm.pattern(query);
        List<GroupMessage> rows = dao.search(groupId, membership.joinedAfterId(), pattern, beforeId, limit + 1);
        List<GroupMessage> page = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        return new GroupDtos.Page(page, rows.size() > limit ? page.get(page.size() - 1).id() : null);
    }

    public GroupMessage message(String authorization, long groupId, long messageId) {
        long userId = access.requireUser(authorization);
        var membership = requireMember(groupId, userId);
        var message = dao.messageById(groupId, messageId, membership.joinedAfterId());
        if (message == null) throw new ChatException(HttpStatus.NOT_FOUND, "消息不存在或不可查看");
        return message;
    }

    public void markRead(String authorization, long groupId, long messageId) {
        long userId = access.requireUser(authorization);
        var membership = requireMember(groupId, userId);
        if (messageId <= membership.joinedAfterId()
                || dao.validMessage(groupId, messageId, membership.joinedAfterId()) == null)
            throw new ChatException(HttpStatus.BAD_REQUEST, "已读消息不属于当前讨论室");
        dao.markRead(groupId, userId, messageId);
    }

    private GroupDao.Membership requireMember(long groupId, long userId) {
        if (groupId <= 0) throw new ChatException(HttpStatus.BAD_REQUEST, "讨论室编号无效");
        var membership = dao.membership(groupId, userId);
        if (membership == null) throw new ChatException(HttpStatus.FORBIDDEN, "无权访问此讨论室");
        return membership;
    }

    private List<Long> validateFriends(String authorization, long owner, List<Long> friendIds) {
        if (friendIds == null || friendIds.size() > 20
                || friendIds.stream().anyMatch(id -> id == null || id <= 0 || id == owner)
                || new HashSet<>(friendIds).size() != friendIds.size())
            throw new ChatException(HttpStatus.BAD_REQUEST, "好友列表无效");
        for (long id : friendIds) access.requireFriend(authorization, id);
        return friendIds;
    }
}
