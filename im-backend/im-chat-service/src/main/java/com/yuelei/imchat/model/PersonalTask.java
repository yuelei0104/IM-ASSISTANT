package com.yuelei.imchat.model;

import java.time.LocalDateTime;
import java.time.LocalDate;

public record PersonalTask(long id, String title, boolean completed, LocalDateTime createdAt,
                           Long sourceGroupId, Long sourceMessageId, LocalDate dueDate) { }
