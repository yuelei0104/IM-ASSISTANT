package com.yuelei.imchat.model;

import java.util.List;

public record GroupChat(long id, String name, long ownerId, List<Long> memberIds, long unreadCount) { }
