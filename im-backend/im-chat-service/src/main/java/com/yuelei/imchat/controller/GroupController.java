package com.yuelei.imchat.controller;

import com.yuelei.imchat.dto.GroupDtos;
import com.yuelei.imchat.dto.MessageDtos.Send;
import com.yuelei.imchat.model.GroupChat;
import com.yuelei.imchat.model.GroupMessage;
import com.yuelei.imchat.service.GroupService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chats/groups")
public class GroupController {
    private final GroupService groups;

    public GroupController(GroupService groups) { this.groups = groups; }

    @PostMapping
    public GroupChat create(@RequestHeader(value = "Authorization", required = false) String auth,
                            @Valid @RequestBody GroupDtos.Create request) {
        return groups.create(auth, request);
    }

    @GetMapping
    public GroupDtos.Groups list(@RequestHeader(value = "Authorization", required = false) String auth,
                                 @RequestParam(required = false) Long beforeId,
                                 @RequestParam(defaultValue = "50") int limit) {
        return groups.groups(auth, beforeId, limit);
    }

    @GetMapping("/{groupId}")
    public GroupChat detail(@RequestHeader(value = "Authorization", required = false) String auth,
                            @PathVariable long groupId) {
        return groups.detail(auth, groupId);
    }

    @PostMapping("/{groupId}/members")
    public GroupChat invite(@RequestHeader(value = "Authorization", required = false) String auth,
                            @PathVariable long groupId, @Valid @RequestBody GroupDtos.Invite request) {
        return groups.invite(auth, groupId, request);
    }

    @PostMapping("/{groupId}/messages")
    public GroupMessage send(@RequestHeader(value = "Authorization", required = false) String auth,
                             @PathVariable long groupId, @Valid @RequestBody Send request) {
        return groups.send(auth, groupId, request);
    }

    @GetMapping("/{groupId}/messages")
    public GroupDtos.Page history(@RequestHeader(value = "Authorization", required = false) String auth,
                                  @PathVariable long groupId,
                                  @RequestParam(required = false) Long beforeId,
                                  @RequestParam(defaultValue = "50") int limit) {
        return groups.history(auth, groupId, beforeId, limit);
    }

    @PostMapping("/{groupId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@RequestHeader(value = "Authorization", required = false) String auth,
                     @PathVariable long groupId, @RequestBody GroupDtos.Read request) {
        groups.markRead(auth, groupId, request.lastReadMessageId());
    }
}
