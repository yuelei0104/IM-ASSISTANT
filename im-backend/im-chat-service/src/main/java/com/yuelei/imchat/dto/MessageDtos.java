package com.yuelei.imchat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class MessageDtos {
    private MessageDtos() { }

    public record Send(@NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String clientMsgId,
                       @NotBlank @Size(max = 4000) String body) { }

    public record Page(java.util.List<com.yuelei.imchat.model.DirectMessage> messages, Long nextBeforeId) { }
    public record SyncPage(java.util.List<com.yuelei.imchat.model.DirectMessage> messages, Long nextAfterId) { }
}
