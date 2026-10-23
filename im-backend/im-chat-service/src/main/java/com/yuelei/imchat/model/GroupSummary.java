package com.yuelei.imchat.model;

import java.time.LocalDateTime;

public record GroupSummary(long id, long groupId, long requesterId, long firstMessageId,
                           long lastMessageId, String content, LocalDateTime createdAt) { }
