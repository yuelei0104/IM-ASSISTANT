package com.yuelei.imchat.model;

import java.time.LocalDateTime;

public record GroupMessage(long id, long groupId, long senderId, String clientMsgId,
                           String body, LocalDateTime createdAt) { }
