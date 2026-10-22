package com.yuelei.imchat.dto;

import com.yuelei.imchat.model.GroupChat;
import com.yuelei.imchat.model.GroupMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class GroupDtos {
    private GroupDtos() { }
    public record Create(@NotBlank @Size(max = 30) String name, @NotNull @Size(max = 20) List<Long> friendIds) { }
    public record Invite(@NotNull @Size(min = 1, max = 20) List<Long> friendIds) { }
    public record Groups(List<GroupChat> items, Long nextBeforeId) { }
    public record Page(List<GroupMessage> messages, Long nextBeforeId) { }
    public record Read(long lastReadMessageId) { }
}
