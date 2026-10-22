package com.yuelei.imchat.service;

import com.yuelei.imchat.dao.GroupDao;
import com.yuelei.imchat.dto.GroupDtos;
import com.yuelei.imchat.dto.MessageDtos.Send;
import com.yuelei.imchat.exception.ChatException;
import com.yuelei.imchat.model.GroupChat;
import com.yuelei.imchat.model.GroupMessage;
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

    public GroupService(GroupDao dao, UserAccessClient access, ApplicationEventPublisher events) {
        this.dao = dao;
        this.access = access;
        this.events = events;
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
        dao.lockGroup(groupId); // Serialize invitations with sends before capturing the join cursor.
        var existing = new HashSet<>(dao.memberIds(groupId));
        if (existing.size() + invitees.size() > 50 || invitees.stream().anyMatch(existing::contains))
            throw new ChatException(HttpStatus.CONFLICT, "成员已加入或讨论室人数超过 50");
        long cursor = dao.latestId(groupId);
        for (long id : invitees) dao.addMember(groupId, id, cursor);
        events.publishEvent(new GroupEvent(groupId, 0, dao.memberIds(groupId)));
        return detail(groupId);
    }

    @Transactional
    public GroupMessage send(String authorization, long groupId, Send input) {
        long sender = access.requireUser(authorization);
        if (dao.lockGroup(groupId) == null) throw new ChatException(HttpStatus.NOT_FOUND, "讨论室不存在");
        requireMember(groupId, sender);
        String body = input.body().trim();
        if (body.isEmpty()) throw new ChatException(HttpStatus.BAD_REQUEST, "消息不能为空");
        dao.insertMessage(groupId, sender, input.clientMsgId(), body);
        GroupMessage saved = dao.byClientId(sender, input.clientMsgId());
        if (saved == null) throw new ChatException(HttpStatus.INTERNAL_SERVER_ERROR, "消息保存失败");
        if (saved.groupId() != groupId || !saved.body().equals(body))
            throw new ChatException(HttpStatus.CONFLICT, "消息编号已用于另一条消息");
        events.publishEvent(new GroupEvent(groupId, saved.id(), dao.memberIds(groupId)));
        return saved;
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
