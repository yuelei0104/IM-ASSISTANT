package com.yuelei.imchat.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Advisory user-to-node leases. The local WebSocket map remains authoritative. */
@Component
public class ChatNodeDirectory {
    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final Duration KEY_TTL = Duration.ofMinutes(3);
    private final StringRedisTemplate redis;
    private final String nodeId;

    public ChatNodeDirectory(StringRedisTemplate redis, @Value("${server.port:8082}") int port) {
        this.redis = redis;
        // A new incarnation must never remove a route owned by an older process.
        this.nodeId = port + "-" + UUID.randomUUID();
    }

    public String nodeId() { return nodeId; }

    public void renew(long userId) {
        String key = key(userId);
        redis.opsForZSet().add(key, nodeId, Instant.now().plus(LEASE).toEpochMilli());
        redis.expire(key, KEY_TTL);
    }

    public void remove(long userId) {
        redis.opsForZSet().remove(key(userId), nodeId);
    }

    public Set<String> activeNodes(long userId) {
        String key = key(userId);
        long now = Instant.now().toEpochMilli();
        redis.opsForZSet().removeRangeByScore(key, 0, now);
        Set<String> nodes = redis.opsForZSet().rangeByScore(key, now + 1, Double.POSITIVE_INFINITY);
        return nodes == null ? Set.of() : Set.copyOf(nodes);
    }

    private static String key(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        return "im:chat:routes:" + userId;
    }
}
