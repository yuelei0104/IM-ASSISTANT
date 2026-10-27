package com.yuelei.imchat.service;

import com.yuelei.imchat.dao.OutboxDao;
import com.yuelei.imchat.model.OutboxRow;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@ConditionalOnProperty(name = "im.kafka.enabled", havingValue = "true")
public class KafkaOutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(KafkaOutboxRelay.class);
    private final SqlSessionFactory sessions;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;

    public KafkaOutboxRelay(SqlSessionFactory sessions, KafkaTemplate<String, String> kafka,
                            @Value("${im.kafka.topic}") String topic) {
        this.sessions = sessions;
        this.kafka = kafka;
        this.topic = topic;
    }

    @Scheduled(fixedDelayString = "${im.kafka.outbox.poll-ms:1000}")
    public void publish() {
        // One open SQL session owns the MySQL advisory lock for this batch.
        // Another chat node can take over as soon as this connection closes.
        try (var session = sessions.openSession(true)) {
            OutboxDao outbox = session.getMapper(OutboxDao.class);
            if (!Integer.valueOf(1).equals(outbox.tryLock())) return;
            try {
                for (OutboxRow row : outbox.pending(20)) {
                    try {
                        kafka.send(topic, row.getEventKey(), row.getPayload()).get(5, TimeUnit.SECONDS);
                        if (outbox.markPublished(row.getId()) != 1)
                            throw new IllegalStateException("Outbox row not marked published: " + row.getId());
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (ExecutionException | TimeoutException ex) {
                        // A timeout may actually have reached Kafka. Retrying
                        // can duplicate the ID-only notification; clients sync by ID.
                        log.warn("Kafka notification relay paused at outbox row {}", row.getId(), ex);
                        return;
                    }
                }
            } finally {
                outbox.releaseLock();
            }
        } catch (RuntimeException ex) {
            log.warn("Kafka outbox relay unavailable; pending rows will be retried", ex);
        }
    }

    @Scheduled(fixedDelayString = "${im.kafka.outbox.cleanup-ms:3600000}")
    public void cleanup() {
        try (var session = sessions.openSession(true)) {
            session.getMapper(OutboxDao.class).deletePublished(500);
        } catch (RuntimeException ex) {
            log.warn("Outbox cleanup failed; next run will retry", ex);
        }
    }
}
