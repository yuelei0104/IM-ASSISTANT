package com.yuelei.imbackend.controller;

import com.yuelei.imbackend.dto.AuthDtos.*;
import com.yuelei.imbackend.service.AuthService;
import com.yuelei.imcommon.Identity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/codes")
    public CodeSent code(@Valid @RequestBody SendCode input,
                         @RequestHeader(value = "Authorization", required = false) String authorization) {
        return auth.sendCode(input, authorization);
    }

    @PostMapping("/registration-check")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void checkRegistration(@Valid @RequestBody RegistrationCheck input) {
        auth.checkRegistration(input);
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Account register(@Valid @RequestBody Register input) { return auth.register(input); }

    @PostMapping("/login/password")
    public LoginResult passwordLogin(@Valid @RequestBody PasswordLogin input, HttpServletRequest request) {
        // Use the socket peer; untrusted X-Forwarded-For headers must not bypass the IP limit.
        return auth.passwordLogin(input, request.getRemoteAddr());
    }

    @PostMapping("/login/code")
    public LoginResult codeLogin(@Valid @RequestBody CodeLogin input) { return auth.codeLogin(input); }

    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPassword input) { auth.resetPassword(input); }

    @GetMapping("/me")
    public Account me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return auth.me(authorization);
    }

    @GetMapping("/identity")
    public Identity identity(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return new Identity(auth.requireUserId(authorization));
    }

    @PostMapping("/bind")
    public Account bind(@RequestHeader(value = "Authorization", required = false) String authorization,
                        @Valid @RequestBody BindContact input) {
        return auth.bind(authorization, input);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        auth.logout(authorization);
    }
}
