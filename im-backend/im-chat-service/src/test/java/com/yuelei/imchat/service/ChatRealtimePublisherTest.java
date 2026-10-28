package com.yuelei.imchat.service;

import com.yuelei.imchat.config.ChatRealtimeConfig;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ChatRealtimePublisherTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ChatNodeDirectory directory = mock(ChatNodeDirectory.class);
    private final ChatRealtimePublisher publisher = new ChatRealtimePublisher(redis, directory);

    @Test
    void sendsDirectNoticeOnceToEachRelevantNode() {
        when(directory.activeNodes(23)).thenReturn(Set.of("8082-a", "8083-b"));
        when(directory.activeNodes(9)).thenReturn(Set.of("8083-b"));

        publisher.publish("91:23:9");

        verify(redis).convertAndSend(ChatRealtimeConfig.nodeTopic("8082-a"), "91:23:9");
        verify(redis).convertAndSend(ChatRealtimeConfig.nodeTopic("8083-b"), "91:23:9");
        verify(redis, times(2)).convertAndSend(anyString(), anyString());
        verify(redis, never()).convertAndSend(eq(ChatRealtimeConfig.TOPIC), anyString());
    }

    @Test
    void groupAndMentionUseTheSameRoutingRules() {
        when(directory.activeNodes(9)).thenReturn(Set.of("8082-a"));
        when(directory.activeNodes(23)).thenReturn(Set.of("8082-a"));
        publisher.publish("G:7:90:9,23");
        publisher.publish("N:9,23");
        verify(redis).convertAndSend(ChatRealtimeConfig.nodeTopic("8082-a"), "G:7:90:9,23");
        verify(redis).convertAndSend(ChatRealtimeConfig.nodeTopic("8082-a"), "N:9,23");
        verify(redis, times(2)).convertAndSend(anyString(), anyString());
    }

    @Test
    void missingRecipientLeaseBroadcastsJustOnce() {
        when(directory.activeNodes(23)).thenReturn(Set.of("8082-a"));
        when(directory.activeNodes(9)).thenReturn(Set.of());
        publisher.publish("R:23:9:91");
        verify(redis).convertAndSend(ChatRealtimeConfig.TOPIC, "R:23:9:91");
        verify(redis, times(1)).convertAndSend(anyString(), anyString());
    }

    @Test
    void routeLookupFailurePropagatesForKafkaRetry() {
        when(directory.activeNodes(23)).thenThrow(new IllegalStateException("redis unavailable"));
        when(directory.activeNodes(9)).thenReturn(Set.of());
        assertThrows(IllegalStateException.class, () -> publisher.publish("91:23:9"));
        verify(directory).activeNodes(23);
        verifyNoInteractions(redis);
    }

    @Test
    void rejectsMalformedNoticeBeforePublishing() {
        assertEquals(Set.of(23L, 9L), RealtimeEvent.recipients("R:23:9:91"));
        assertEquals(Set.of(9L, 23L), RealtimeEvent.recipients("G:7:90:9,23,9"));
        assertEquals(Set.of(), RealtimeEvent.recipients("N:"));
        assertThrows(IllegalArgumentException.class, () -> publisher.publish("invalid:event"));
        verifyNoInteractions(redis);
    }
}
