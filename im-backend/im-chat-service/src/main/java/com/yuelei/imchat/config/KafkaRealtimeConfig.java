package com.yuelei.imchat.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableKafka
@EnableScheduling
@ConditionalOnProperty(name = "im.kafka.enabled", havingValue = "true")
public class KafkaRealtimeConfig {
    @Bean
    public DefaultErrorHandler kafkaNotificationErrorHandler() {
        // The default handler eventually skips a failing record. Redis failure
        // must never advance the Kafka offset instead.
        return new DefaultErrorHandler(new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
