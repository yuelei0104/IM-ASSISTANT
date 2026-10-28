package com.yuelei.imchat.service;

import com.yuelei.imchat.model.DirectMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnProperty(name = "im.kafka.enabled", havingValue = "false", matchIfMissing = true)
public class DirectMessageNotifier {
    private static final Logger log = LoggerFactory.getLogger(DirectMessageNotifier.class);
    private final ChatRealtimePublisher publisher;

    public DirectMessageNotifier(ChatRealtimePublisher publisher) { this.publisher = publisher; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void delivered(DirectMessage message) {
        try {
            // Notification contains only IDs. The receiver fetches authoritative data over authenticated HTTP.
            publisher.publish(RealtimeEvent.direct(message).payload());
        } catch (RuntimeException ex) {
            // Persistence already committed; reconnect/periodic sync retrieves the message.
            log.warn("Chat realtime notification failed for message {}", message.id(), ex);
        }
    }
}
