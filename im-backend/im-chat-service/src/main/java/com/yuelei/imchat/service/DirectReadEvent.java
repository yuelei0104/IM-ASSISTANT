package com.yuelei.imchat.service;

public record DirectReadEvent(long readerId, long peerId, long lastReadMessageId) { }
