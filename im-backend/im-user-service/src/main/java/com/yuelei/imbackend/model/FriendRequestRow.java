package com.yuelei.imbackend.model;

public record FriendRequestRow(long id, long fromId, long toId, String state) { }
