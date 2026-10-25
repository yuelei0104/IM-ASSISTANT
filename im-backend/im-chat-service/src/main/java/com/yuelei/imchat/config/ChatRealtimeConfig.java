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
            try {
                if (parts.length == 2 && "N".equals(parts[0])) {
                    java.util.Arrays.stream(parts[1].split(",")).filter(part -> !part.isEmpty())
                            .mapToLong(Long::parseLong).forEach(sockets::notifyMention);
                } else if (parts.length == 4 && "R".equals(parts[0])) {
                    sockets.notifyRead(Long.parseLong(parts[1]), Long.parseLong(parts[2]),
                            Long.parseLong(parts[3]));
                } else if (parts.length == 4 && "G".equals(parts[0])) {
                    var recipients = java.util.Arrays.stream(parts[3].split(","))
                            .filter(part -> !part.isEmpty()).map(Long::parseLong).toList();
                    sockets.notifyGroup(Long.parseLong(parts[1]), Long.parseLong(parts[2]), recipients);
                } else if (parts.length == 3) {
                    sockets.notifyMessage(Long.parseLong(parts[0]), Long.parseLong(parts[1]),
                            Long.parseLong(parts[2]));
                }
            } catch (NumberFormatException ignored) { }
        }, new ChannelTopic(TOPIC));
        return container;
    }
}
