package com.yuelei.imchat.service;

import com.yuelei.imchat.dao.MessageDao;
import com.yuelei.imchat.dto.MessageDtos.Page;
import com.yuelei.imchat.dto.MessageDtos.Send;
import com.yuelei.imchat.dto.MessageDtos.SyncPage;
import com.yuelei.imchat.exception.ChatException;
import com.yuelei.imchat.model.DirectMessage;
import com.yuelei.imchat.model.MessageRecall;
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
        // The upsert obtains an exclusive pair lock even when the row already exists.
        // Keep each pair's IDs in commit order, including concurrent sends from both users.
        messages.ensurePair(Math.min(senderId, friendId), Math.max(senderId, friendId));
        messages.lockPair(Math.min(senderId, friendId), Math.max(senderId, friendId));
        messages.insert(Math.min(senderId, friendId), Math.max(senderId, friendId),
                senderId, friendId, input.clientMsgId(), body);
        DirectMessage saved = messages.byClientId(senderId, input.clientMsgId());
        if (saved == null) throw new ChatException(HttpStatus.INTERNAL_SERVER_ERROR, "消息保存失败");
        if (saved.recipientId() != friendId || saved.recalledAt() != null || !saved.body().equals(body))
            throw new ChatException(HttpStatus.CONFLICT, "消息编号已用于另一条消息");
        messages.trackInbox(friendId, senderId);
        events.publishEvent(saved);
        return saved;
    }

    @Transactional
    public void recall(String authorization, long friendId, long messageId) {
        long senderId = access.requireUser(authorization);
        if (friendId <= 0 || friendId == senderId || messageId <= 0)
            throw new ChatException(HttpStatus.BAD_REQUEST, "消息编号无效");
        DirectMessage message = messages.byId(messageId);
        if (message == null || message.senderId() != senderId || message.recipientId() != friendId)
            throw new ChatException(HttpStatus.NOT_FOUND, "只能撤回自己发送的消息");
        if (message.recalledAt() != null) return;
        if (messages.recall(messageId, senderId) != 1) {
            // A concurrent recall may have committed while this request waited on the row lock.
            if (messages.byId(messageId).recalledAt() != null) return;
            throw new ChatException(HttpStatus.CONFLICT, "只能在发送后两分钟内撤回");
        }
        messages.addRecall(messageId, Math.min(senderId, friendId), Math.max(senderId, friendId));
        events.publishEvent(messages.byId(messageId));
    }

    public com.yuelei.imchat.dto.MessageDtos.RecallPage recalls(String authorization, long friendId,
                                                                  long afterId, int limit) {
        long userId = access.requireUser(authorization);
        if (friendId <= 0 || friendId == userId || afterId < 0 || limit < 1 || limit > 50)
            throw new ChatException(HttpStatus.BAD_REQUEST, "撤回游标无效");
        List<MessageRecall> rows = messages.recalls(Math.min(userId, friendId),
                Math.max(userId, friendId), afterId, limit + 1);
        List<MessageRecall> page = rows.subList(0, Math.min(rows.size(), limit));
        return new com.yuelei.imchat.dto.MessageDtos.RecallPage(page,
                rows.size() > limit ? page.get(page.size() - 1).id() : null);
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

    public SyncPage sync(String authorization, long friendId, long afterId, int limit) {
        long userId = access.requireUser(authorization);
        if (friendId <= 0 || friendId == userId || afterId < 0 || limit < 1 || limit > 50)
            throw new ChatException(HttpStatus.BAD_REQUEST, "增量游标无效");
        List<DirectMessage> rows = messages.after(Math.min(userId, friendId),
                Math.max(userId, friendId), afterId, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<DirectMessage> page = rows.subList(0, Math.min(limit, rows.size()));
        return new SyncPage(page, hasMore ? page.get(page.size() - 1).id() : null);
    }

    public Page search(String authorization, long friendId, String query, Long beforeId, int limit) {
        long userId = access.requireUser(authorization);
        if (friendId <= 0 || friendId == userId || beforeId != null && beforeId <= 0 || limit < 1 || limit > 50)
            throw new ChatException(HttpStatus.BAD_REQUEST, "分页参数无效");
        String pattern = SearchTerm.pattern(query);
        List<DirectMessage> rows = messages.search(Math.min(userId, friendId), Math.max(userId, friendId),
                pattern, beforeId, limit + 1);
        List<DirectMessage> page = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        return new Page(page, rows.size() > limit ? page.get(page.size() - 1).id() : null);
    }

    public List<UnreadCount> unread(String authorization) {
        return messages.unreadCounts(access.requireUser(authorization));
    }

    public long peerReadCursor(String authorization, long peerId) {
        long userId = access.requireUser(authorization);
        if (peerId <= 0 || peerId == userId)
            throw new ChatException(HttpStatus.BAD_REQUEST, "会话账号无效");
        Long cursor = messages.readCursor(peerId, userId);
        return cursor == null ? 0 : cursor;
    }

    @Transactional
    public void markRead(String authorization, long peerId, long lastReadMessageId) {
        long userId = access.requireUser(authorization);
        if (peerId <= 0 || peerId == userId || lastReadMessageId <= 0
                || messages.receivedCount(userId, peerId, lastReadMessageId) != 1) {
            throw new ChatException(HttpStatus.BAD_REQUEST, "已读消息不属于当前会话");
        }
        Long prior = messages.readCursor(userId, peerId);
        if (prior != null && prior >= lastReadMessageId) return;
        messages.markRead(userId, peerId, lastReadMessageId);
        // The cursor is monotonically increasing even when multiple devices read together.
        Long saved = messages.readCursor(userId, peerId);
        events.publishEvent(new DirectReadEvent(userId, peerId, saved == null ? lastReadMessageId : saved));
    }
}
