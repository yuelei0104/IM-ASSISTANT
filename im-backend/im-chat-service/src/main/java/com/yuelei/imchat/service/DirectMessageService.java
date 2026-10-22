package com.yuelei.imchat.service;

import com.yuelei.imchat.dao.MessageDao;
import com.yuelei.imchat.dto.MessageDtos.Page;
import com.yuelei.imchat.dto.MessageDtos.Send;
import com.yuelei.imchat.exception.ChatException;
import com.yuelei.imchat.model.DirectMessage;
import com.yuelei.imchat.model.UnreadCount;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class DirectMessageService {
    private final MessageDao messages;
    private final UserAccessClient access;
    private final ApplicationEventPublisher events;

    public DirectMessageService(MessageDao messages, UserAccessClient access, ApplicationEventPublisher events) {
        this.messages = messages;
        this.access = access;
        this.events = events;
    }

    @Transactional
    public DirectMessage send(String authorization, long friendId, Send input) {
        if (friendId <= 0) throw new ChatException(HttpStatus.BAD_REQUEST, "好友账号无效");
        long senderId = access.requireFriend(authorization, friendId);
        String body = input.body().trim();
        if (body.isEmpty()) throw new ChatException(HttpStatus.BAD_REQUEST, "消息不能为空");
        messages.insert(Math.min(senderId, friendId), Math.max(senderId, friendId),
                senderId, friendId, input.clientMsgId(), body);
        DirectMessage saved = messages.byClientId(senderId, input.clientMsgId());
        if (saved == null) throw new ChatException(HttpStatus.INTERNAL_SERVER_ERROR, "消息保存失败");
        if (saved.recipientId() != friendId || !saved.body().equals(body))
            throw new ChatException(HttpStatus.CONFLICT, "消息编号已用于另一条消息");
        messages.trackInbox(friendId, senderId);
        events.publishEvent(saved);
        return saved;
    }

    public Page history(String authorization, long friendId, Long beforeId, int limit) {
        long userId = access.requireUser(authorization);
        if (friendId <= 0 || friendId == userId || beforeId != null && beforeId <= 0 || limit < 1 || limit > 50)
            throw new ChatException(HttpStatus.BAD_REQUEST, "分页参数无效");
        List<DirectMessage> descending = messages.history(Math.min(userId, friendId),
                Math.max(userId, friendId), beforeId, limit + 1);
        boolean hasOlder = descending.size() > limit;
        List<DirectMessage> page = new ArrayList<>(descending.subList(0, Math.min(limit, descending.size())));
        Long nextBeforeId = hasOlder ? page.get(page.size() - 1).id() : null;
        Collections.reverse(page);
        return new Page(page, nextBeforeId);
    }

    public List<UnreadCount> unread(String authorization) {
        return messages.unreadCounts(access.requireUser(authorization));
    }

    public void markRead(String authorization, long peerId, long lastReadMessageId) {
        long userId = access.requireUser(authorization);
        if (peerId <= 0 || peerId == userId || lastReadMessageId <= 0
                || messages.receivedCount(userId, peerId, lastReadMessageId) != 1) {
            throw new ChatException(HttpStatus.BAD_REQUEST, "已读消息不属于当前会话");
        }
        messages.markRead(userId, peerId, lastReadMessageId);
    }
}
