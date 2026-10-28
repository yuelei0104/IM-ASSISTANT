package com.yuelei.imchat.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnProperty(name = "im.kafka.enabled", havingValue = "false", matchIfMissing = true)
public class GroupNotifier {
    private static final Logger log = LoggerFactory.getLogger(GroupNotifier.class);
    private final ChatRealtimePublisher publisher;

    public GroupNotifier(ChatRealtimePublisher publisher) { this.publisher = publisher; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void delivered(GroupEvent event) {
        try {
            // Only IDs cross Pub/Sub; clients read messages using their own bearer token.
            publisher.publish(RealtimeEvent.group(event).payload());
        } catch (RuntimeException ex) {
            log.warn("Group notification failed for group {}", event.groupId(), ex);
        }
    }
}
