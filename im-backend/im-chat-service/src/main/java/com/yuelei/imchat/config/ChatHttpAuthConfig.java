package com.yuelei.imchat.config;

import com.yuelei.imchat.service.UserAccessClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Validate bearer identity before controller services open database transactions. */
@Configuration
public class ChatHttpAuthConfig implements WebMvcConfigurer {
    private final UserAccessClient access;

    public ChatHttpAuthConfig(UserAccessClient access) { this.access = access; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (!"OPTIONS".equals(request.getMethod()))
                    access.requireUser(request.getHeader("Authorization"));
                return true;
            }
        }).addPathPatterns("/api/chats/**").excludePathPatterns("/api/chats/ws");
    }
}
