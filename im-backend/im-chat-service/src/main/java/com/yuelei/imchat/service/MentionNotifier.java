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
public class MentionNotifier {
    private static final Logger log = LoggerFactory.getLogger(MentionNotifier.class);
    private final StringRedisTemplate redis;
    public MentionNotifier(StringRedisTemplate redis) { this.redis = redis; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void delivered(MentionEvent event) {
        try {
            redis.convertAndSend(ChatRealtimeConfig.TOPIC, RealtimeEvent.mention(event).payload());
        } catch (RuntimeException ex) {
            log.warn("Mention notification failed", ex);
        }
    }
}
