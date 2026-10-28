package com.yuelei.imchat.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "im.kafka.enabled", havingValue = "true")
public class KafkaRealtimeBridge {
    private final ChatRealtimePublisher publisher;

    public KafkaRealtimeBridge(ChatRealtimePublisher publisher) { this.publisher = publisher; }

    @KafkaListener(topics = "${im.kafka.topic}")
    public void notifyNodes(String payload, Acknowledgment acknowledgment) {
        // Only the original transaction's ID-only payload is passed to Redis.
        // Do not acknowledge on a Redis exception: the consumer must retry.
        publisher.publish(payload);
        acknowledgment.acknowledge();
    }
}
