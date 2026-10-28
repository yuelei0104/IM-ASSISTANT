package com.yuelei.imchat;

import com.yuelei.imchat.dao.OutboxDao;
import com.yuelei.imchat.model.DirectMessage;
import com.yuelei.imchat.model.OutboxRow;
import com.yuelei.imchat.service.KafkaOutboxRelay;
import com.yuelei.imchat.service.KafkaOutboxMetrics;
import com.yuelei.imchat.service.KafkaRealtimeBridge;
import com.yuelei.imchat.service.ChatRealtimePublisher;
import com.yuelei.imchat.service.NotificationOutboxWriter;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
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

        KafkaOutboxMetrics metrics = mock(KafkaOutboxMetrics.class);
        var relay = new KafkaOutboxRelay(factory, kafka, metrics, TOPIC);
        relay.publish();
        verify(dao, never()).markPublished(anyLong());
        verify(metrics).relayFailed();
        relay.publish();
        verify(dao).markPublished(7);
        verify(dao, times(2)).releaseLock();
    }

    @Test
    void metricsExposePendingRowsAgeAndRelayFailuresWithoutBreakingOnDatabaseOutage() {
        OutboxDao dao = mock(OutboxDao.class);
        when(dao.countPending()).thenReturn(4L);
        when(dao.oldestPendingSeconds()).thenReturn(75L);
        var registry = new SimpleMeterRegistry();
        var metrics = new KafkaOutboxMetrics(dao, registry);
        assertEquals(4.0, registry.get("im.chat.outbox.pending").gauge().value());
        assertEquals(75.0, registry.get("im.chat.outbox.oldest.seconds").gauge().value());
        metrics.relayFailed();
        assertEquals(1.0, registry.get("im.chat.outbox.relay.failures").counter().count());
        when(dao.countPending()).thenThrow(new IllegalStateException("database unavailable"));
        assertTrue(Double.isNaN(registry.get("im.chat.outbox.pending").gauge().value()));
    }

    @Test
    void redisFailureLeavesKafkaOffsetUnacknowledged() {
        ChatRealtimePublisher publisher = mock(ChatRealtimePublisher.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        var bridge = new KafkaRealtimeBridge(publisher);
        doThrow(new IllegalStateException("redis unavailable"))
                .doNothing().when(publisher).publish("91:23:9");
        assertThrows(IllegalStateException.class, () -> bridge.notifyNodes("91:23:9", acknowledgment));
        verifyNoInteractions(acknowledgment);
        bridge.notifyNodes("91:23:9", acknowledgment);
        verify(acknowledgment).acknowledge();
    }
}
