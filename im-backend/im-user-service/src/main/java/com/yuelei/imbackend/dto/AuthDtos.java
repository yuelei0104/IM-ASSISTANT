package com.yuelei.imbackend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() { }

    public record SendCode(@NotBlank String purpose, @NotBlank String contactType,
                           @NotBlank String contact) { }
    public record CodeSent(int expiresInSeconds, int retryAfterSeconds, String devCode) { }
    public record RegistrationCheck(@NotBlank @Size(max = 24) String nickname,
                                    @NotBlank String handle, String phone, String email) { }
    public record Register(@NotBlank @Size(max = 24) String nickname,
                           @NotBlank String handle, String phone, String email,
                           @NotBlank String password, String phoneCode, String emailCode) { }
    public record PasswordLogin(@NotBlank String contactType, @NotBlank String contact,
                                @NotBlank String password) { }
    public record CodeLogin(@NotBlank String contactType, @NotBlank String contact,
                            @NotBlank String code) { }
    public record BindContact(@NotBlank String contactType, @NotBlank String contact,
                              @NotBlank String password, @NotBlank String code) { }
    public record ResetPassword(@NotBlank String contactType, @NotBlank String contact,
                                @NotBlank String code, @NotBlank String newPassword) { }
    public record Account(long id, String nickname, String handle, String phone, String email) { }
    public record LoginResult(String token, Account account) { }
    public record Error(String message) { }
}
