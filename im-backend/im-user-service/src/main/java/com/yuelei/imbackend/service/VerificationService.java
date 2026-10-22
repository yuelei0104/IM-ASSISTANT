package com.yuelei.imbackend.service;

import com.yuelei.imbackend.dto.AuthDtos.CodeSent;
import com.yuelei.imbackend.exception.AuthException;
import com.yuelei.imbackend.util.Contacts.Purpose;
import com.yuelei.imbackend.util.Contacts.Type;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

/** Local development code delivery. Replace with an SMS/email provider before deployment. */
@Service
public class VerificationService {
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration COOLDOWN = Duration.ofSeconds(60);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final StringRedisTemplate redis;
    private final boolean devMode;

    public VerificationService(StringRedisTemplate redis, @Value("${im.auth.dev-mode:false}") boolean devMode) {
        this.redis = redis;
        this.devMode = devMode;
    }

    public CodeSent send(Purpose purpose, Type type, String contact) {
        if (!devMode) throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE, "验证码发送服务尚未配置");
        String key = key(purpose, type, contact);
        Boolean acquired = redis.opsForValue().setIfAbsent(key + ":cooldown", "1", COOLDOWN);
        if (!Boolean.TRUE.equals(acquired))
            throw new AuthException(HttpStatus.TOO_MANY_REQUESTS, "请等待 60 秒后重新获取验证码");
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        redis.opsForValue().set(key, digest(code), CODE_TTL);
        redis.delete(key + ":attempts");
        return new CodeSent(300, 60, code);
    }

    public void verify(Purpose purpose, Type type, String contact, String code) {
        if (code == null || !code.matches("[0-9]{6}"))
            throw new AuthException(HttpStatus.BAD_REQUEST, "请输入 6 位验证码");
        String key = key(purpose, type, contact);
        String saved = redis.opsForValue().get(key);
        if (saved == null)
            throw new AuthException(HttpStatus.BAD_REQUEST, "验证码不存在或已过期");
        Long attempts = redis.opsForValue().increment(key + ":attempts");
        if (attempts != null && attempts == 1) redis.expire(key + ":attempts", CODE_TTL);
        if (attempts != null && attempts > 5) {
            redis.delete(key);
            throw new AuthException(HttpStatus.TOO_MANY_REQUESTS, "验证码错误次数过多，请重新获取");
        }
        if (!MessageDigest.isEqual(saved.getBytes(StandardCharsets.UTF_8),
                digest(code).getBytes(StandardCharsets.UTF_8)))
            throw new AuthException(HttpStatus.BAD_REQUEST, "验证码不正确");
    }

    public void consume(Purpose purpose, Type type, String contact) {
        String key = key(purpose, type, contact);
        redis.delete(key);
        redis.delete(key + ":attempts");
    }

    private static String key(Purpose purpose, Type type, String contact) {
        return "im:auth:code:" + purpose + ":" + type + ":" + digest(contact);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
