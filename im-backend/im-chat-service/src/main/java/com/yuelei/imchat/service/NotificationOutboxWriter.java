package com.yuelei.imchat.service;

import com.yuelei.imchat.dao.OutboxDao;
import com.yuelei.imchat.model.DirectMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnProperty(name = "im.kafka.enabled", havingValue = "true")
public class NotificationOutboxWriter {
    private final OutboxDao outbox;

    public NotificationOutboxWriter(OutboxDao outbox) { this.outbox = outbox; }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void direct(DirectMessage message) { add(RealtimeEvent.direct(message)); }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void group(GroupEvent event) { add(RealtimeEvent.group(event)); }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void mention(MentionEvent event) { add(RealtimeEvent.mention(event)); }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void read(DirectReadEvent event) { add(RealtimeEvent.read(event)); }

    private void add(RealtimeEvent.Notification event) {
        // The MyBatis insert joins the same transaction as the message or read cursor.
        if (outbox.insert(event.key(), event.payload()) != 1)
            throw new IllegalStateException("Notification outbox insert failed");
    }
}
