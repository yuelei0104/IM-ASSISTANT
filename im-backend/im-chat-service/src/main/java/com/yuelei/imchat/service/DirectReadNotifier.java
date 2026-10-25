package com.yuelei.imchat.service;

import com.yuelei.imchat.config.ChatRealtimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class DirectReadNotifier {
    private static final Logger log = LoggerFactory.getLogger(DirectReadNotifier.class);
    private final StringRedisTemplate redis;

    public DirectReadNotifier(StringRedisTemplate redis) { this.redis = redis; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void read(DirectReadEvent event) {
        try {
            redis.convertAndSend(ChatRealtimeConfig.TOPIC,
                    "R:" + event.readerId() + ":" + event.peerId() + ":" + event.lastReadMessageId());
        } catch (RuntimeException ex) {
            // The durable cursor remains queryable on reconnect even if Pub/Sub is unavailable.
            log.warn("Chat read notification failed for reader {}", event.readerId(), ex);
        }
    }
}
