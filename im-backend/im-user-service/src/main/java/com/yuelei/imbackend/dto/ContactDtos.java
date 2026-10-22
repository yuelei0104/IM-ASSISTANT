package com.yuelei.imbackend.dto;

import jakarta.validation.constraints.Size;

public final class ContactDtos {
    private ContactDtos() { }

    public record Invite(String handle, String phone, @Size(max = 120) String note) { }
}
