package com.yuelei.imchat.service;

import com.yuelei.imchat.config.ChatRealtimeConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "im.kafka.enabled", havingValue = "true")
public class KafkaRealtimeBridge {
    private final StringRedisTemplate redis;

    public KafkaRealtimeBridge(StringRedisTemplate redis) { this.redis = redis; }

    @KafkaListener(topics = "${im.kafka.topic}")
    public void notifyNodes(String payload, Acknowledgment acknowledgment) {
        // Only the original transaction's ID-only payload is passed to Redis.
        // Do not acknowledge on a Redis exception: the consumer must retry.
        redis.convertAndSend(ChatRealtimeConfig.TOPIC, payload);
        acknowledgment.acknowledge();
    }
}
