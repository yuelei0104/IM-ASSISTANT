package com.yuelei.imchat.service;

import java.util.List;

public record GroupEvent(long groupId, long messageId, List<Long> recipients) { }
