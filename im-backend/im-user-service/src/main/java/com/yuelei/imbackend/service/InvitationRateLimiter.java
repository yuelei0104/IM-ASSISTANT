package com.yuelei.imbackend.service;

import com.yuelei.imbackend.exception.AuthException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class InvitationRateLimiter {
    private static final DefaultRedisScript<Long> LIMIT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], 86400) end
            if count > 20 then return 1 end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;

    public InvitationRateLimiter(StringRedisTemplate redis) { this.redis = redis; }

    public void check(long userId) {
        Long limited = redis.execute(LIMIT, List.of("im:contacts:invite:" + userId));
        if (limited == null || limited == 1)
            throw new AuthException(HttpStatus.TOO_MANY_REQUESTS, "今日好友申请次数已用完，请明天再试");
    }
}
