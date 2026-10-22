package com.yuelei.imchat.service;

import com.yuelei.imchat.config.ChatRealtimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class GroupNotifier {
    private static final Logger log = LoggerFactory.getLogger(GroupNotifier.class);
    private final StringRedisTemplate redis;

    public GroupNotifier(StringRedisTemplate redis) { this.redis = redis; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void delivered(GroupEvent event) {
        try {
            // Only IDs cross Pub/Sub; clients read messages using their own bearer token.
            redis.convertAndSend(ChatRealtimeConfig.TOPIC,
                    "G:" + event.groupId() + ":" + event.messageId() + ":" +
                            event.recipients().stream().map(String::valueOf)
                                    .collect(java.util.stream.Collectors.joining(",")));
        } catch (RuntimeException ex) {
            log.warn("Group notification failed for group {}", event.groupId(), ex);
        }
    }
}
