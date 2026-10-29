package com.yuelei.imbackend;

import com.yuelei.imbackend.exception.AuthException;
import com.yuelei.imbackend.service.VerificationService;
import com.yuelei.imbackend.util.Contacts.Purpose;
import com.yuelei.imbackend.util.Contacts.Type;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VerificationDeliveryTest {
    @Test
    @SuppressWarnings("unchecked")
    void productionEmailNeverReturnsTheCodeAndPhoneIsUnavailable() {
        var redis = mock(StringRedisTemplate.class);
        var values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(60)))).thenReturn(true);
        var sender = mock(JavaMailSender.class);
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        var service = new VerificationService(redis, false, provider, "mail.example.com", "noreply@example.com");

        var sent = service.send(Purpose.REGISTER, Type.EMAIL, "a@example.com");
        assertNull(sent.devCode());
        verify(sender).send(any(SimpleMailMessage.class));
        assertThrows(AuthException.class,
                () -> service.send(Purpose.REGISTER, Type.PHONE, "13800138000"));
    }
}
