package com.yuelei.imbackend;

import com.yuelei.imbackend.dao.UserDao;
import com.yuelei.imbackend.dto.AuthDtos.ResetPassword;
import com.yuelei.imbackend.exception.AuthException;
import com.yuelei.imbackend.model.UserAccount;
import com.yuelei.imbackend.service.AuthService;
import com.yuelei.imbackend.service.SessionService;
import com.yuelei.imbackend.service.VerificationService;
import com.yuelei.imbackend.util.Contacts.Purpose;
import com.yuelei.imbackend.util.Contacts.Type;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AuthServiceRecoveryTest {
    private final UserDao users = mock(UserDao.class);
    private final VerificationService codes = mock(VerificationService.class);
    private final SessionService sessions = mock(SessionService.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final AuthService auth = new AuthService(users, codes, sessions, passwords);

    @Test
    void resetRequiresTheBoundContactCodeThenRevokesExistingSessions() {
        when(users.byEmail("test@example.com"))
                .thenReturn(new UserAccount(42L, "test", "test_42", null,
                        "test@example.com", "old-hash"));
        when(passwords.encode("NewPassword123")).thenReturn("new-hash");
        when(users.updatePassword(42L, "new-hash")).thenReturn(1);

        auth.resetPassword(new ResetPassword("email", "TEST@EXAMPLE.COM", "123456", "NewPassword123"));

        var sequence = inOrder(codes, users, sessions);
        sequence.verify(codes).verify(Purpose.RESET, Type.EMAIL, "test@example.com", "123456");
        sequence.verify(users).updatePassword(42L, "new-hash");
        sequence.verify(sessions).revokeAll(42L);
        sequence.verify(codes).consume(Purpose.RESET, Type.EMAIL, "test@example.com");
    }

    @Test
    void unknownContactDoesNotChangePasswordOrInvalidateSessions() {
        assertThrows(AuthException.class, () -> auth.resetPassword(
                new ResetPassword("phone", "13800138000", "123456", "NewPassword123")));
        verify(users, never()).updatePassword(anyLong(), anyString());
        verifyNoInteractions(codes, sessions);
    }
}
