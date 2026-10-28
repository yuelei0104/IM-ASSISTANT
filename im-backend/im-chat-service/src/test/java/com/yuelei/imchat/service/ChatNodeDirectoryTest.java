package com.yuelei.imchat.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatNodeDirectoryTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ZSetOperations<String, String> sorted = mock(ZSetOperations.class);

    @Test
    void leaseUsesUniqueProcessIdentityAndExpiresWithoutHeartbeat() {
        when(redis.opsForZSet()).thenReturn(sorted);
        var first = new ChatNodeDirectory(redis, 8082);
        var restarted = new ChatNodeDirectory(redis, 8082);
        assertNotEquals(first.nodeId(), restarted.nodeId());
        assertTrue(first.nodeId().startsWith("8082-"));

        first.renew(42);
        verify(sorted).add(eq("im:chat:routes:42"), eq(first.nodeId()),
                doubleThat(expiry -> expiry > System.currentTimeMillis()
                        && expiry <= System.currentTimeMillis() + 61_000));
        verify(redis).expire("im:chat:routes:42", Duration.ofMinutes(3));
        first.remove(42);
        verify(sorted).remove("im:chat:routes:42", first.nodeId());
    }

    @Test
    void queriesOnlyUnexpiredNodeLeases() {
        when(redis.opsForZSet()).thenReturn(sorted);
        when(sorted.rangeByScore(eq("im:chat:routes:42"), anyDouble(),
                eq(Double.POSITIVE_INFINITY))).thenReturn(Set.of("8082-a", "8083-b"));
        var directory = new ChatNodeDirectory(redis, 8082);
        assertEquals(Set.of("8082-a", "8083-b"), directory.activeNodes(42));
        verify(sorted).removeRangeByScore(eq("im:chat:routes:42"), eq(0.0), anyDouble());
    }
}
