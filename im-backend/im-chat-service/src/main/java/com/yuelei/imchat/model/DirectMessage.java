package com.yuelei.imchat.model;

import java.time.LocalDateTime;

public record DirectMessage(long id, long senderId, long recipientId, String clientMsgId,
                            String body, LocalDateTime createdAt) { }
