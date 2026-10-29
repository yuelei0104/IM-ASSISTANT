package com.yuelei.imbackend.service;

import com.yuelei.imbackend.dto.AuthDtos.CodeSent;
import com.yuelei.imbackend.exception.AuthException;
import com.yuelei.imbackend.util.Contacts.Purpose;
import com.yuelei.imbackend.util.Contacts.Type;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/** Development code preview or configured SMTP email; phone delivery requires a provider. */
@Service
public class VerificationService {
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration COOLDOWN = Duration.ofSeconds(60);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DefaultRedisScript<Long> VERIFY_AND_CONSUME = new DefaultRedisScript<>("""
            local saved = redis.call('GET', KEYS[1])
            if not saved then return -1 end
            local attempts = redis.call('INCR', KEYS[2])
            if attempts == 1 then redis.call('EXPIRE', KEYS[2], 300) end
            if attempts > 5 then
                redis.call('DEL', KEYS[1])
                return -2
            end
            if saved ~= ARGV[1] then return 0 end
            redis.call('DEL', KEYS[1], KEYS[2])
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final boolean devMode;
    private final JavaMailSender mail;
    private final String mailHost;
    private final String mailFrom;

    public VerificationService(StringRedisTemplate redis, @Value("${im.auth.dev-mode:false}") boolean devMode,
                               ObjectProvider<JavaMailSender> mail,
                               @Value("${spring.mail.host:}") String mailHost,
                               @Value("${im.auth.mail-from:}") String mailFrom) {
        this.redis = redis;
        this.devMode = devMode;
        this.mail = mail.getIfAvailable();
        this.mailHost = mailHost;
        this.mailFrom = mailFrom;
    }

    public CodeSent send(Purpose purpose, Type type, String contact) {
        if (!devMode && (type != Type.EMAIL || mailHost.isBlank() || mailFrom.isBlank() || mail == null))
            throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE,
                    type == Type.PHONE ? "手机号验证码服务尚未配置，请使用邮箱" : "邮箱验证码服务尚未配置");
        String key = key(purpose, type, contact);
        Boolean acquired = redis.opsForValue().setIfAbsent(key + ":cooldown", "1", COOLDOWN);
        if (!Boolean.TRUE.equals(acquired))
            throw new AuthException(HttpStatus.TOO_MANY_REQUESTS, "请等待 60 秒后重新获取验证码");
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        redis.opsForValue().set(key, digest(code), CODE_TTL);
        redis.delete(key + ":attempts");
        if (!devMode) {
            try {
                var message = new SimpleMailMessage();
                message.setFrom(mailFrom);
                message.setTo(contact);
                message.setSubject("IM Assistant 验证码");
                message.setText("验证码：" + code + "。5 分钟内有效。如果不是你本人操作，请忽略本邮件。");
                mail.send(message);
            } catch (MailException ex) {
                redis.delete(List.of(key, key + ":attempts", key + ":cooldown"));
                throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE, "邮箱验证码发送失败，请稍后重试");
            }
        }
        return new CodeSent(300, 60, devMode ? code : null);
    }

    public void verifyAndConsume(Purpose purpose, Type type, String contact, String code) {
        if (code == null || !code.matches("[0-9]{6}"))
            throw new AuthException(HttpStatus.BAD_REQUEST, "请输入 6 位验证码");
        String key = key(purpose, type, contact);
        Long result = redis.execute(VERIFY_AND_CONSUME, List.of(key, key + ":attempts"), digest(code));
        if (result == null || result == -1)
            throw new AuthException(HttpStatus.BAD_REQUEST, "验证码不存在或已过期");
        if (result == -2)
            throw new AuthException(HttpStatus.TOO_MANY_REQUESTS, "验证码错误次数过多，请重新获取");
        if (result != 1)
            throw new AuthException(HttpStatus.BAD_REQUEST, "验证码不正确");
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
