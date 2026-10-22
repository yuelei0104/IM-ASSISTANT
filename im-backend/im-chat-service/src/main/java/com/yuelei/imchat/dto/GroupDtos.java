package com.yuelei.imchat.dto;

import com.yuelei.imchat.model.GroupChat;
import com.yuelei.imchat.model.GroupMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public final class GroupDtos {
    private GroupDtos() { }
    public record Create(@NotBlank @Size(max = 30) String name, @NotNull @Size(max = 20) List<Long> friendIds) { }
    public record Invite(@NotNull @Size(min = 1, max = 20) List<Long> friendIds) { }
    public record Groups(List<GroupChat> items, Long nextBeforeId) { }
    public record Page(List<GroupMessage> messages, Long nextBeforeId) { }
    public record SyncPage(List<GroupMessage> messages, Long nextAfterId) { }
    public record Read(long lastReadMessageId) { }
    public record SendGroup(
            @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String clientMsgId,
            @NotBlank @Size(max = 4000) String body,
            @NotNull @Size(max = 10) List<Long> mentionedUserIds) { }
}
