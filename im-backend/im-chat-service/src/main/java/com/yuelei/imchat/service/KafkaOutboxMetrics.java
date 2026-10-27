package com.yuelei.imchat.service;

import com.yuelei.imchat.dao.OutboxDao;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

@Component
@ConditionalOnProperty(name = "im.kafka.enabled", havingValue = "true")
public class KafkaOutboxMetrics {
    private final Counter relayFailures;

    public KafkaOutboxMetrics(OutboxDao outbox, MeterRegistry registry) {
        Gauge.builder("im.chat.outbox.pending", outbox, dao -> read(dao::countPending))
                .description("Unpublished notification rows across all chat nodes")
                .register(registry);
        Gauge.builder("im.chat.outbox.oldest.seconds", outbox, dao -> read(dao::oldestPendingSeconds))
                .description("Age in seconds of the oldest unpublished notification")
                .register(registry);
        relayFailures = Counter.builder("im.chat.outbox.relay.failures")
                .description("Kafka relay publish or database failures on this chat node")
                .register(registry);
    }

    public void relayFailed() {
        relayFailures.increment();
    }

    private static double read(LongSupplier query) {
        try {
            return query.getAsLong();
        } catch (RuntimeException ex) {
            // Keep metric collection from failing when MySQL is unavailable.
            return Double.NaN;
        }
    }
}
