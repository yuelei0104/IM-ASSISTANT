package com.yuelei.imbackend.service;

import com.yuelei.imbackend.exception.AuthException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

@Service
public class LoginRateLimiter {
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int ACCOUNT_LIMIT = 5;
    private static final int IP_LIMIT = 30;
    private static final DefaultRedisScript<Long> FAILURE = new DefaultRedisScript<>("""
            local account = redis.call('INCR', KEYS[1])
            if account == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            local ip = redis.call('INCR', KEYS[2])
            if ip == 1 then redis.call('EXPIRE', KEYS[2], ARGV[1]) end
            if account >= tonumber(ARGV[2]) or ip >= tonumber(ARGV[3]) then return 1 end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public LoginRateLimiter(StringRedisTemplate redis) { this.redis = redis; }

    public void check(String account, String ip) {
        Long accountFailures = count(accountKey(account));
        Long ipFailures = count(ipKey(ip));
        if (accountFailures >= ACCOUNT_LIMIT || ipFailures >= IP_LIMIT) locked();
    }

    public void failed(String account, String ip) {
        Long locked = redis.execute(FAILURE, List.of(accountKey(account), ipKey(ip)),
                String.valueOf(WINDOW.toSeconds()), String.valueOf(ACCOUNT_LIMIT), String.valueOf(IP_LIMIT));
        if (locked == null || locked == 1) locked();
    }

    public void succeeded(String account) { redis.delete(accountKey(account)); }

    private long count(String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? 0 : Long.parseLong(value);
    }

    private static void locked() {
        throw new AuthException(HttpStatus.TOO_MANY_REQUESTS, "登录尝试过多，请 15 分钟后再试");
    }

    private static String accountKey(String value) { return "im:auth:login:account:" + digest(value); }
    private static String ipKey(String value) { return "im:auth:login:ip:" + digest(value); }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
