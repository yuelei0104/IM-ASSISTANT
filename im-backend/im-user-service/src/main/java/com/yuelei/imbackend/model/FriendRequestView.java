package com.yuelei.imbackend.model;

import java.time.LocalDateTime;

public record FriendRequestView(long id, long fromId, long toId,
                                long peerId, String peerNickname, String peerHandle,
                                String note, String state, LocalDateTime createdAt) { }
