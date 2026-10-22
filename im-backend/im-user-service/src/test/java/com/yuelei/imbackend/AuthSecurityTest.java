package com.yuelei.imbackend;

import com.yuelei.imbackend.dao.UserDao;
import com.yuelei.imbackend.dto.AuthDtos.PasswordLogin;
import com.yuelei.imbackend.dto.AuthDtos.RegistrationCheck;
import com.yuelei.imbackend.exception.AuthException;
import com.yuelei.imbackend.model.UserAccount;
import com.yuelei.imbackend.service.AuthService;
import com.yuelei.imbackend.service.LoginRateLimiter;
import com.yuelei.imbackend.service.SessionService;
import com.yuelei.imbackend.service.VerificationService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthSecurityTest {
    private final UserDao users = mock(UserDao.class);
    private final VerificationService codes = mock(VerificationService.class);
    private final SessionService sessions = mock(SessionService.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final LoginRateLimiter limits = mock(LoginRateLimiter.class);
    private final AuthService service = new AuthService(users, codes, sessions, passwords, limits);

    @Test
    void publicRegistrationCheckDoesNotQueryAccountAvailability() {
        service.checkRegistration(new RegistrationCheck("测试用户", "user_01", "13800138000", null));
        verifyNoInteractions(users);
    }

    @Test
    void wrongPasswordCountsAgainstBothAccountAndIpButNeverCreatesSession() {
        when(users.byEmail("test@example.com"))
                .thenReturn(new UserAccount(7, "test", "test_01", null, "test@example.com", "hash"));
        assertThrows(AuthException.class, () -> service.passwordLogin(
                new PasswordLogin("email", "TEST@EXAMPLE.COM", "wrong-password"), "127.0.0.1"));
        verify(limits).check("EMAIL:test@example.com", "127.0.0.1");
        verify(limits).failed("EMAIL:test@example.com", "127.0.0.1");
        verifyNoInteractions(sessions);
    }

    @Test
    void successfulPasswordClearsAccountFailures() {
        when(users.byEmail("test@example.com"))
                .thenReturn(new UserAccount(7, "test", "test_01", null, "test@example.com", "hash"));
        when(passwords.matches("Password123", "hash")).thenReturn(true);
        service.passwordLogin(new PasswordLogin("email", "test@example.com", "Password123"), "127.0.0.1");
        verify(limits).succeeded("EMAIL:test@example.com");
        verify(sessions).create(7);
    }
}
