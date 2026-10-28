package com.yuelei.imchat.service;

import com.yuelei.imchat.model.DirectMessage;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
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

    /** The notification envelope contains IDs only; local sockets still filter by identity. */
    public static Set<Long> recipients(String payload) {
        String[] parts = payload.split(":", -1);
        if (parts.length == 3) return Set.copyOf(Arrays.asList(
                Long.parseLong(parts[1]), Long.parseLong(parts[2])));
        if (parts.length == 4 && "R".equals(parts[0]))
            return Set.copyOf(Arrays.asList(Long.parseLong(parts[1]), Long.parseLong(parts[2])));
        String list;
        if (parts.length == 4 && "G".equals(parts[0])) list = parts[3];
        else if (parts.length == 2 && "N".equals(parts[0])) list = parts[1];
        else throw new IllegalArgumentException("Invalid realtime event");
        var result = new LinkedHashSet<Long>();
        Arrays.stream(list.split(",")).filter(id -> !id.isEmpty())
                .map(Long::parseLong).forEach(result::add);
        return result;
    }
}
