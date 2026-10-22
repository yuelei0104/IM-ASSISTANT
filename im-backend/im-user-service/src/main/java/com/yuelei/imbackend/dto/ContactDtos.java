package com.yuelei.imbackend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class ContactDtos {
    private ContactDtos() { }

    public record Invite(@NotBlank String handle, @Size(max = 120) String note) { }
}
