package com.yuelei.imchat.config;

import com.yuelei.imchat.service.ChatSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

@Configuration
public class ChatRealtimeConfig {
    public static final String TOPIC = "im:chat:messages";

    @Bean
    public RedisMessageListenerContainer chatMessages(RedisConnectionFactory factory, ChatSocketHandler sockets) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener((message, pattern) -> {
            String[] parts = new String(message.getBody(), StandardCharsets.UTF_8).split(":", -1);
            if (parts.length != 3) return;
            try {
                sockets.notifyMessage(Long.parseLong(parts[0]), Long.parseLong(parts[1]),
                        Long.parseLong(parts[2]));
            } catch (NumberFormatException ignored) { }
        }, new ChannelTopic(TOPIC));
        return container;
    }
}
