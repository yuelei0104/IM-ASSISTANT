package com.yuelei.imchat.model;

import java.time.LocalDateTime;

public record MentionNotice(long id, long groupId, long messageId, long senderId,
                            String groupName, String body, LocalDateTime createdAt,
                            LocalDateTime readAt, String kind) { }
