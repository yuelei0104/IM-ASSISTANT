package com.yuelei.imchat.model;

import java.time.LocalDateTime;

public record PersonalTask(long id, String title, boolean completed, LocalDateTime createdAt,
                           Long sourceGroupId, Long sourceMessageId) { }
