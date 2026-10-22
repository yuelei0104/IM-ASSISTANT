package com.yuelei.imbackend.service;

import com.yuelei.imbackend.dao.UserDao;
import com.yuelei.imbackend.dto.AuthDtos.*;
import com.yuelei.imbackend.exception.AuthException;
import com.yuelei.imbackend.model.NewUser;
import com.yuelei.imbackend.model.UserAccount;
import com.yuelei.imbackend.util.Contacts;
import com.yuelei.imbackend.util.Contacts.Purpose;
import com.yuelei.imbackend.util.Contacts.Type;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {
    private final UserDao users;
    private final VerificationService codes;
    private final SessionService sessions;
    private final PasswordEncoder passwords;
    private final LoginRateLimiter loginLimits;
    private final String dummyHash;

    public AuthService(UserDao users, VerificationService codes, SessionService sessions,
                       PasswordEncoder passwords, LoginRateLimiter loginLimits) {
        this.users = users;
        this.codes = codes;
        this.sessions = sessions;
        this.passwords = passwords;
        this.loginLimits = loginLimits;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public CodeSent sendCode(SendCode input, String authorization) {
        Purpose purpose = Contacts.purpose(input.purpose());
        Type type = Contacts.type(input.contactType());
        String contact = Contacts.normalize(type, input.contact());
        if (purpose == Purpose.BIND) {
            UserAccount account = current(authorization);
            if (type == Type.PHONE && account.phone() != null
                    || type == Type.EMAIL && account.email() != null)
                throw new AuthException(HttpStatus.CONFLICT, "此账号已有该联系方式");
        }
        return codes.send(purpose, type, contact);
    }

    @Transactional
    public Account register(Register input) {
        Contacts.password(input.password());
        var details = formatDetails(input.nickname(), input.handle(), input.phone(), input.email());
        String nickname = details.nickname();
        String handle = details.handle();
        String phone = details.phone();
        String email = details.email();
        if (phone != null) codes.verifyAndConsume(Purpose.REGISTER, Type.PHONE, phone, input.phoneCode());
        if (email != null) codes.verifyAndConsume(Purpose.REGISTER, Type.EMAIL, email, input.emailCode());
        ensureAvailable(details);
        try {
            NewUser user = new NewUser(nickname, handle, phone, email, passwords.encode(input.password()));
            users.create(user);
            return new Account(user.getId(), nickname, handle, phone, email);
        } catch (DataIntegrityViolationException ex) {
            throw new AuthException(HttpStatus.CONFLICT, "账号、手机号或邮箱已被使用");
        }
    }

    public void checkRegistration(RegistrationCheck input) {
        formatDetails(input.nickname(), input.handle(), input.phone(), input.email());
    }

    private RegistrationDetails formatDetails(String rawNickname, String rawHandle,
                                              String rawPhone, String rawEmail) {
        String nickname = Contacts.nickname(rawNickname);
        String handle = Contacts.handle(rawHandle);
        String phone = Contacts.optional(Type.PHONE, rawPhone);
        String email = Contacts.optional(Type.EMAIL, rawEmail);
        if (phone == null && email == null)
            throw new AuthException(HttpStatus.BAD_REQUEST, "请至少填写手机号或邮箱");
        return new RegistrationDetails(nickname, handle, phone, email);
    }

    private void ensureAvailable(RegistrationDetails details) {
        if (users.countByHandle(details.handle()) > 0 || details.phone() != null && users.countByPhone(details.phone()) > 0
                || details.email() != null && users.countByEmail(details.email()) > 0)
            throw new AuthException(HttpStatus.CONFLICT, "账号、手机号或邮箱已被使用");
    }

    private record RegistrationDetails(String nickname, String handle, String phone, String email) { }

    public LoginResult passwordLogin(PasswordLogin input, String clientIp) {
        Type type = Contacts.type(input.contactType());
        String contact = Contacts.normalize(type, input.contact());
        String key = type + ":" + contact;
        loginLimits.check(key, clientIp);
        UserAccount account = byContact(type, contact);
        boolean matched = passwords.matches(input.password(), account == null ? dummyHash : account.passwordHash());
        if (account == null || !matched) {
            loginLimits.failed(key, clientIp);
            throw new AuthException(HttpStatus.UNAUTHORIZED, "账号或密码不正确");
        }
        loginLimits.succeeded(key);
        return new LoginResult(sessions.create(account.id()), view(account));
    }

    public LoginResult codeLogin(CodeLogin input) {
        Type type = Contacts.type(input.contactType());
        String contact = Contacts.normalize(type, input.contact());
        codes.verifyAndConsume(Purpose.LOGIN, type, contact, input.code());
        UserAccount account = Optional.ofNullable(byContact(type, contact))
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "账号或验证码不正确"));
        return new LoginResult(sessions.create(account.id()), view(account));
    }

    public void resetPassword(ResetPassword input) {
        Type type = Contacts.type(input.contactType());
        String contact = Contacts.normalize(type, input.contact());
        Contacts.password(input.newPassword());
        codes.verifyAndConsume(Purpose.RESET, type, contact, input.code());
        UserAccount account = byContact(type, contact);
        if (account == null) return;
        if (users.updatePassword(account.id(), passwords.encode(input.newPassword())) != 1)
            throw new AuthException(HttpStatus.CONFLICT, "账号状态已变化，请重试");
        sessions.revokeAll(account.id());
    }

    public Account me(String authorization) { return view(current(authorization)); }

    public long requireUserId(String authorization) { return sessions.requireUserId(authorization); }

    public Account bind(String authorization, BindContact input) {
        UserAccount account = current(authorization);
        Type type = Contacts.type(input.contactType());
        String contact = Contacts.normalize(type, input.contact());
        if (type == Type.PHONE && account.phone() != null
                || type == Type.EMAIL && account.email() != null)
            throw new AuthException(HttpStatus.CONFLICT, "此账号已有该联系方式");
        if (!passwords.matches(input.password(), account.passwordHash()))
            throw new AuthException(HttpStatus.UNAUTHORIZED, "密码不正确");
        codes.verifyAndConsume(Purpose.BIND, type, contact, input.code());
        if (contactExists(type, contact))
            throw new AuthException(HttpStatus.CONFLICT, "此联系方式已绑定其他账号");
        try {
            int updated = type == Type.PHONE ? users.bindPhone(account.id(), contact)
                    : users.bindEmail(account.id(), contact);
            if (updated != 1)
                throw new AuthException(HttpStatus.CONFLICT, "此账号已有该联系方式");
        } catch (DataIntegrityViolationException ex) {
            throw new AuthException(HttpStatus.CONFLICT, "此联系方式已绑定其他账号");
        }
        return view(current(authorization));
    }

    public void logout(String authorization) { sessions.logout(authorization); }

    private UserAccount byContact(Type type, String contact) {
        return type == Type.PHONE ? users.byPhone(contact) : users.byEmail(contact);
    }

    private boolean contactExists(Type type, String contact) {
        return type == Type.PHONE ? users.countByPhone(contact) > 0
                : users.countByEmail(contact) > 0;
    }

    private UserAccount current(String authorization) {
        return Optional.ofNullable(users.byId(sessions.requireUserId(authorization)))
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "账号不存在，请重新登录"));
    }

    private static Account view(UserAccount user) {
        return new Account(user.id(), user.nickname(), user.handle(), user.phone(), user.email());
    }
}
