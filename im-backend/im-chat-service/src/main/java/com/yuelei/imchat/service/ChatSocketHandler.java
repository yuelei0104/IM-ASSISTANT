package com.yuelei.imchat.service;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class ChatSocketHandler extends TextWebSocketHandler {
    private static final Duration AUTH_TTL = Duration.ofMinutes(6);
    private final UserAccessClient access;
    private final ConcurrentHashMap<String, WebSocketSession> sockets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> identities = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> verified = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> opened = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Set<String>> byUser = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweep = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "im-chat-ws-auth-expiry");
        thread.setDaemon(true);
        return thread;
    });

    public ChatSocketHandler(UserAccessClient access) {
        this.access = access;
        sweep.scheduleAtFixedRate(this::closeExpired, 10, 10, TimeUnit.SECONDS);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sockets.put(session.getId(), session);
        opened.put(session.getId(), Instant.now());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        if (!payload.startsWith("AUTH ") || payload.length() > 261 || payload.length() <= 5) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        long userId;
        try {
            userId = access.requireUser("Bearer " + payload.substring(5));
        } catch (RuntimeException ex) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        Long previous = identities.putIfAbsent(session.getId(), userId);
        if (previous != null && previous != userId) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        byUser.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(session.getId());
        verified.put(session.getId(), Instant.now());
        send(session, "READY");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        remove(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        remove(session);
        if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
    }

    public void notifyMessage(long messageId, long senderId, long recipientId) {
        push(senderId, "MSG " + messageId + " " + senderId + " " + recipientId);
        push(recipientId, "MSG " + messageId + " " + senderId + " " + recipientId);
    }

    private void push(long userId, String payload) {
        for (String id : byUser.getOrDefault(userId, Set.of())) {
            WebSocketSession session = sockets.get(id);
            Instant time = verified.get(id);
            if (session == null || time == null || time.plus(AUTH_TTL).isBefore(Instant.now())) {
                if (session != null) close(session);
                continue;
            }
            send(session, payload);
        }
    }

    private void send(WebSocketSession session, String payload) {
        try {
            synchronized (session) {
                if (session.isOpen()) session.sendMessage(new TextMessage(payload));
            }
        } catch (IOException ex) {
            close(session);
        }
    }

    private void closeExpired() {
        Instant now = Instant.now();
        for (WebSocketSession session : sockets.values()) {
            String id = session.getId();
            Instant auth = verified.get(id);
            if (auth == null && opened.get(id) != null && opened.get(id).plusSeconds(10).isBefore(now)
                    || auth != null && auth.plus(AUTH_TTL).isBefore(now)) close(session);
        }
    }

    private void close(WebSocketSession session) {
        remove(session);
        try { if (session.isOpen()) session.close(CloseStatus.POLICY_VIOLATION); }
        catch (IOException ignored) { }
    }

    private void remove(WebSocketSession session) {
        String id = session.getId();
        sockets.remove(id);
        opened.remove(id);
        verified.remove(id);
        Long userId = identities.remove(id);
        if (userId != null) {
            Set<String> userSockets = byUser.get(userId);
            if (userSockets != null) {
                userSockets.remove(id);
                if (userSockets.isEmpty()) byUser.remove(userId, userSockets);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        sweep.shutdownNow();
        for (WebSocketSession session : sockets.values()) close(session);
    }
}
