package com.yuelei.imbackend.service;

import com.yuelei.imbackend.exception.AuthException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class SessionService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration TTL = Duration.ofDays(7);
    private final StringRedisTemplate redis;

    public SessionService(StringRedisTemplate redis) { this.redis = redis; }

    public String create(long userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(key(token), userId + ":" + version(userId), TTL);
        return token;
    }

    public long requireUserId(String authorization) {
        String token = bearer(authorization);
        String id = redis.opsForValue().get(key(token));
        if (id == null) throw new AuthException(HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录");
        String[] parts = id.split(":", -1);
        if (parts.length != 1 && parts.length != 2)
            throw new AuthException(HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录");
        try {
            long userId = Long.parseLong(parts[0]);
            String issuedVersion = parts.length == 1 ? "0" : parts[1];
            if (!issuedVersion.equals(version(userId)))
                throw new AuthException(HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录");
            return userId;
        } catch (NumberFormatException ex) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录");
        }
    }

    public void revokeAll(long userId) {
        redis.opsForValue().increment(versionKey(userId));
    }

    private String version(long userId) {
        String value = redis.opsForValue().get(versionKey(userId));
        return value == null ? "0" : value;
    }

    private static String versionKey(long userId) { return "im:auth:session-version:" + userId; }

    public void logout(String authorization) {
        redis.delete(key(bearer(authorization)));
    }

    private static String bearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.length() <= 7)
            throw new AuthException(HttpStatus.UNAUTHORIZED, "请先登录");
        return authorization.substring(7);
    }

    private static String key(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return "im:auth:session:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
