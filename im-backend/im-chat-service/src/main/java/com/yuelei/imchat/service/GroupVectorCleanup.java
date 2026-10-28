package com.yuelei.imchat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class GroupVectorCleanup {
    private static final Logger log = LoggerFactory.getLogger(GroupVectorCleanup.class);
    private final GroupVectorStore vectors;

    public GroupVectorCleanup(GroupVectorStore vectors) { this.vectors = vectors; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void invalidate(GroupVectorInvalidation event) {
        try {
            if (event.messageId() == null) vectors.removeGroup(event.groupId());
            else vectors.removeMessage(event.groupId(), event.messageId());
        } catch (RuntimeException ex) {
            // MySQL remains authoritative: membership and recall are checked on
            // every question even if this best-effort cleanup must be retried.
            log.warn("Unable to clean group vectors: group={}, message={}, error={}",
                    event.groupId(), event.messageId(), ex.getClass().getSimpleName());
        }
    }
}
