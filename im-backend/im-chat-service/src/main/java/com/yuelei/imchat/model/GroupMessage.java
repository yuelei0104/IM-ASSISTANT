package com.yuelei.imchat.model;

import java.time.LocalDateTime;

public record GroupMessage(long id, long groupId, long senderId, String clientMsgId,
                           String body, String kind, LocalDateTime createdAt) {
    public GroupMessage(long id, long groupId, long senderId, String clientMsgId,
                        String body, LocalDateTime createdAt) {
        this(id, groupId, senderId, clientMsgId, body, "TEXT", createdAt);
    }
}
