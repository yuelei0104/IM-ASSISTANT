package com.yuelei.imchat.service;

import com.yuelei.imchat.model.DirectMessage;

import java.util.stream.Collectors;

/** The broker and Redis carry IDs only; authenticated HTTP reads the message. */
public final class RealtimeEvent {
    private RealtimeEvent() { }

    public record Notification(String key, String payload) { }

    public static Notification direct(DirectMessage message) {
        long low = Math.min(message.senderId(), message.recipientId());
        long high = Math.max(message.senderId(), message.recipientId());
        return new Notification("D:" + low + ":" + high,
                message.id() + ":" + message.senderId() + ":" + message.recipientId());
    }

    public static Notification read(DirectReadEvent event) {
        long low = Math.min(event.readerId(), event.peerId());
        long high = Math.max(event.readerId(), event.peerId());
        return new Notification("D:" + low + ":" + high,
                "R:" + event.readerId() + ":" + event.peerId() + ":" + event.lastReadMessageId());
    }

    public static Notification group(GroupEvent event) {
        return new Notification("G:" + event.groupId(),
                "G:" + event.groupId() + ":" + event.messageId() + ":" +
                        event.recipients().stream().map(String::valueOf).collect(Collectors.joining(",")));
    }

    public static Notification mention(MentionEvent event) {
        return new Notification("N", "N:" + event.recipients().stream()
                .map(String::valueOf).collect(Collectors.joining(",")));
    }
}
