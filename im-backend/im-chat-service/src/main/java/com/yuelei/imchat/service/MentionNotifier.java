package com.yuelei.imchat.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnProperty(name = "im.kafka.enabled", havingValue = "false", matchIfMissing = true)
public class MentionNotifier {
    private static final Logger log = LoggerFactory.getLogger(MentionNotifier.class);
    private final ChatRealtimePublisher publisher;
    public MentionNotifier(ChatRealtimePublisher publisher) { this.publisher = publisher; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void delivered(MentionEvent event) {
        try {
            publisher.publish(RealtimeEvent.mention(event).payload());
        } catch (RuntimeException ex) {
            log.warn("Mention notification failed", ex);
        }
    }
}
