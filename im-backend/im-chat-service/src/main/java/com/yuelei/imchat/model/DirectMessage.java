package com.yuelei.imchat.model;

import java.time.LocalDateTime;

public record DirectMessage(long id, long senderId, long recipientId, String clientMsgId,
                            String body, LocalDateTime createdAt, LocalDateTime recalledAt) {
    public DirectMessage(long id, long senderId, long recipientId, String clientMsgId,
                         String body, LocalDateTime createdAt) {
        this(id, senderId, recipientId, clientMsgId, body, createdAt, null);
    }
}
