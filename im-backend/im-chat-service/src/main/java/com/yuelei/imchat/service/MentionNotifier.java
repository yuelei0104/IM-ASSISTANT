package com.yuelei.imchat.service;

import com.yuelei.imchat.config.ChatRealtimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class MentionNotifier {
    private static final Logger log = LoggerFactory.getLogger(MentionNotifier.class);
    private final StringRedisTemplate redis;
    public MentionNotifier(StringRedisTemplate redis) { this.redis = redis; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void delivered(MentionEvent event) {
        try {
            redis.convertAndSend(ChatRealtimeConfig.TOPIC, "N:" +
                    event.recipients().stream().map(String::valueOf)
                            .collect(java.util.stream.Collectors.joining(",")));
        } catch (RuntimeException ex) {
            log.warn("Mention notification failed", ex);
        }
    }
}
