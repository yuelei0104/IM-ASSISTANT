package com.yuelei.imbackend.model;

public record UserAccount(long id, String nickname, String handle, String phone,
                          String email, String passwordHash) {
}
