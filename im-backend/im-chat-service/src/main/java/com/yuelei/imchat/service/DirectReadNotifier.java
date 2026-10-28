package com.yuelei.imchat.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnProperty(name = "im.kafka.enabled", havingValue = "false", matchIfMissing = true)
public class DirectReadNotifier {
    private static final Logger log = LoggerFactory.getLogger(DirectReadNotifier.class);
    private final ChatRealtimePublisher publisher;

    public DirectReadNotifier(ChatRealtimePublisher publisher) { this.publisher = publisher; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void read(DirectReadEvent event) {
        try {
            publisher.publish(RealtimeEvent.read(event).payload());
        } catch (RuntimeException ex) {
            // The durable cursor remains queryable on reconnect even if Pub/Sub is unavailable.
            log.warn("Chat read notification failed for reader {}", event.readerId(), ex);
        }
    }
}
