package com.yuelei.imchat;

import com.yuelei.imchat.config.ChatRealtimeConfig;
import com.yuelei.imchat.dao.OutboxDao;
import com.yuelei.imchat.model.DirectMessage;
import com.yuelei.imchat.model.OutboxRow;
import com.yuelei.imchat.service.KafkaOutboxRelay;
import com.yuelei.imchat.service.KafkaRealtimeBridge;
import com.yuelei.imchat.service.NotificationOutboxWriter;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KafkaNotificationPipelineTest {
    private static final String TOPIC = "im.chat.notifications.v1";

    @Test
    void outboxContainsOnlyIdsAndConversationKey() {
        OutboxDao dao = mock(OutboxDao.class);
        when(dao.insert(anyString(), anyString())).thenReturn(1);
        var writer = new NotificationOutboxWriter(dao);
        writer.direct(new DirectMessage(91, 23, 9, "client-id", "私密消息", LocalDateTime.now()));
        verify(dao).insert("D:9:23", "91:23:9");
        verify(dao, never()).insert(anyString(), contains("私密消息"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void relayMarksOnlyBrokerAcknowledgedRowsAndRetriesAnUnsentRow() {
        SqlSessionFactory factory = mock(SqlSessionFactory.class);
        SqlSession session = mock(SqlSession.class);
        OutboxDao dao = mock(OutboxDao.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(factory.openSession(true)).thenReturn(session);
        when(session.getMapper(OutboxDao.class)).thenReturn(dao);
        when(dao.tryLock()).thenReturn(1);
        var row = new OutboxRow();
        row.setId(7); row.setEventKey("D:9:23"); row.setPayload("91:23:9");
        when(dao.pending(20)).thenReturn(List.of(row));
        when(dao.markPublished(7)).thenReturn(1);
        var failed = new CompletableFuture<org.springframework.kafka.support.SendResult<String, String>>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafka.send(TOPIC, "D:9:23", "91:23:9"))
                .thenReturn(failed, CompletableFuture.completedFuture(null));

        var relay = new KafkaOutboxRelay(factory, kafka, TOPIC);
        relay.publish();
        verify(dao, never()).markPublished(anyLong());
        relay.publish();
        verify(dao).markPublished(7);
        verify(dao, times(2)).releaseLock();
    }

    @Test
    void redisFailureLeavesKafkaOffsetUnacknowledged() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        var bridge = new KafkaRealtimeBridge(redis);
        when(redis.convertAndSend(ChatRealtimeConfig.TOPIC, "91:23:9"))
                .thenThrow(new IllegalStateException("redis unavailable"));
        assertThrows(IllegalStateException.class, () -> bridge.notifyNodes("91:23:9", acknowledgment));
        verifyNoInteractions(acknowledgment);
        doReturn(1L).when(redis).convertAndSend(ChatRealtimeConfig.TOPIC, "91:23:9");
        bridge.notifyNodes("91:23:9", acknowledgment);
        verify(acknowledgment).acknowledge();
    }
}
