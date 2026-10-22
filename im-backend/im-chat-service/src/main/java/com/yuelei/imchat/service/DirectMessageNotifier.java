package com.yuelei.imchat.service;

import com.yuelei.imchat.config.ChatRealtimeConfig;
import com.yuelei.imchat.model.DirectMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class DirectMessageNotifier {
    private static final Logger log = LoggerFactory.getLogger(DirectMessageNotifier.class);
    private final StringRedisTemplate redis;

    public DirectMessageNotifier(StringRedisTemplate redis) { this.redis = redis; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void delivered(DirectMessage message) {
        try {
            // Notification contains only IDs. The receiver fetches authoritative data over authenticated HTTP.
            redis.convertAndSend(ChatRealtimeConfig.TOPIC,
                    message.id() + ":" + message.senderId() + ":" + message.recipientId());
        } catch (RuntimeException ex) {
            // Persistence already committed; reconnect/periodic sync retrieves the message.
            log.warn("Chat realtime notification failed for message {}", message.id(), ex);
        }
    }
}
