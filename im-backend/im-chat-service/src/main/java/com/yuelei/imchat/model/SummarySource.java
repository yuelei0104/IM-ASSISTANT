package com.yuelei.imchat.model;

import java.time.LocalDateTime;

public record SummarySource(long id, long senderId, String body, String kind,
                            LocalDateTime createdAt) { }
