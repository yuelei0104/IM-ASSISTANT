package com.yuelei.imchat.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class ChatSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatSocketHandler.class);
    private static final Duration AUTH_TTL = Duration.ofMinutes(6);
    private final UserAccessClient access;
    private final ChatNodeDirectory directory;
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

    public ChatSocketHandler(UserAccessClient access, ChatNodeDirectory directory) {
        this.access = access;
        this.directory = directory;
        sweep.scheduleAtFixedRate(this::closeExpired, 10, 10, TimeUnit.SECONDS);
        sweep.scheduleAtFixedRate(this::renewRoutes, 20, 20, TimeUnit.SECONDS);
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
        if (sockets.get(session.getId()) != session || !session.isOpen()) return;
        Long previous = identities.putIfAbsent(session.getId(), userId);
        if (previous != null && previous != userId) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        byUser.compute(userId, (id, sessions) -> {
            if (sessions == null) sessions = ConcurrentHashMap.newKeySet();
            sessions.add(session.getId());
            try { directory.renew(id); }
            catch (RuntimeException ex) { log.warn("Chat route renewal failed for node {}", directory.nodeId()); }
            return sessions;
        });
        verified.put(session.getId(), Instant.now());
        // Authentication may finish after a concurrent transport close. Do not
        // leave behind a lease or acknowledge a connection that has disappeared.
        if (sockets.get(session.getId()) != session || !session.isOpen()) {
            remove(session);
            return;
        }
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

    public void notifyRead(long readerId, long peerId, long lastReadMessageId) {
        String payload = "READ " + readerId + " " + lastReadMessageId;
        push(peerId, payload);
        push(readerId, payload); // Keep the reader's other devices in sync.
    }

    public void notifyGroup(long groupId, long messageId, List<Long> recipients) {
        for (long userId : recipients)
            push(userId, "GROUP " + groupId + " " + messageId);
    }

    public void notifyMention(long recipientId) {
        push(recipientId, "NOTICE");
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

    private void renewRoutes() {
        for (Long userId : byUser.keySet()) {
            byUser.computeIfPresent(userId, (id, sessions) -> {
                if (sessions.isEmpty()) return null;
                try { directory.renew(id); }
                catch (RuntimeException ex) { log.warn("Chat route renewal failed for node {}", directory.nodeId()); }
                return sessions;
            });
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
            byUser.computeIfPresent(userId, (key, sessions) -> {
                sessions.remove(id);
                if (!sessions.isEmpty()) return sessions;
                try { directory.remove(key); }
                catch (RuntimeException ex) { log.warn("Chat route removal failed for node {}", directory.nodeId()); }
                return null;
            });
        }
    }

    @PreDestroy
    public void shutdown() {
        sweep.shutdownNow();
        for (WebSocketSession session : sockets.values()) close(session);
    }
}
