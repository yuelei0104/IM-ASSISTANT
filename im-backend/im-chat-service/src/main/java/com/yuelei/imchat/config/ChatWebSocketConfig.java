package com.yuelei.imchat.config;

import com.yuelei.imchat.service.ChatSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class ChatWebSocketConfig implements WebSocketConfigurer {
    private final ChatSocketHandler handler;
    private final String[] allowedOrigins;

    public ChatWebSocketConfig(ChatSocketHandler handler,
                               @Value("${im.websocket.allowed-origins}") String allowedOrigins) {
        this.handler = handler;
        this.allowedOrigins = allowedOrigins.split(",");
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/chats/ws").setAllowedOrigins(allowedOrigins);
    }
}
