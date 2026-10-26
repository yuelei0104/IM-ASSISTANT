package com.yuelei.imchat.service;

import com.yuelei.imchat.config.ChatRealtimeConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnProperty(name = "im.kafka.enabled", havingValue = "false", matchIfMissing = true)
public class DirectReadNotifier {
    private static final Logger log = LoggerFactory.getLogger(DirectReadNotifier.class);
    private final StringRedisTemplate redis;

    public DirectReadNotifier(StringRedisTemplate redis) { this.redis = redis; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void read(DirectReadEvent event) {
        try {
            redis.convertAndSend(ChatRealtimeConfig.TOPIC, RealtimeEvent.read(event).payload());
        } catch (RuntimeException ex) {
            // The durable cursor remains queryable on reconnect even if Pub/Sub is unavailable.
            log.warn("Chat read notification failed for reader {}", event.readerId(), ex);
        }
    }
}
