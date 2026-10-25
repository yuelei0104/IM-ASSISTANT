package com.yuelei.imchat.model;

import java.time.LocalDateTime;

public record MessageRecall(long id, long messageId, LocalDateTime recalledAt) { }
