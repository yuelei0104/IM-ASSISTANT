package com.yuelei.imchat.controller;

import com.yuelei.imchat.dto.GroupDtos;
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

    @DeleteMapping("/{groupId}/members/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@RequestHeader(value = "Authorization", required = false) String auth,
                      @PathVariable long groupId) {
        groups.leave(auth, groupId);
    }

    @DeleteMapping("/{groupId}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@RequestHeader(value = "Authorization", required = false) String auth,
                       @PathVariable long groupId, @PathVariable long memberId) {
        groups.remove(auth, groupId, memberId);
    }

    @PostMapping("/{groupId}/owner")
    public GroupChat transferOwner(@RequestHeader(value = "Authorization", required = false) String auth,
                                   @PathVariable long groupId, @Valid @RequestBody GroupDtos.TransferOwner request) {
        return groups.transferOwner(auth, groupId, request.memberId());
    }

    @DeleteMapping("/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dissolve(@RequestHeader(value = "Authorization", required = false) String auth,
                         @PathVariable long groupId) {
        groups.dissolve(auth, groupId);
    }

    @PostMapping("/{groupId}/messages")
    public GroupMessage send(@RequestHeader(value = "Authorization", required = false) String auth,
                             @PathVariable long groupId, @Valid @RequestBody GroupDtos.SendGroup request) {
        return groups.send(auth, groupId, request);
    }

    @DeleteMapping("/{groupId}/messages/{messageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recall(@RequestHeader(value = "Authorization", required = false) String auth,
                       @PathVariable long groupId, @PathVariable long messageId) {
        groups.recall(auth, groupId, messageId);
    }

    @GetMapping("/{groupId}/messages/recalls")
    public GroupDtos.RecallPage recalls(@RequestHeader(value = "Authorization", required = false) String auth,
                                        @PathVariable long groupId, @RequestParam(defaultValue = "0") long afterId,
                                        @RequestParam(defaultValue = "50") int limit) {
        return groups.recalls(auth, groupId, afterId, limit);
    }

    @GetMapping("/{groupId}/messages")
    public GroupDtos.Page history(@RequestHeader(value = "Authorization", required = false) String auth,
                                  @PathVariable long groupId,
                                  @RequestParam(required = false) Long beforeId,
                                  @RequestParam(defaultValue = "50") int limit) {
        return groups.history(auth, groupId, beforeId, limit);
    }

    @GetMapping("/{groupId}/messages/sync")
    public GroupDtos.SyncPage sync(@RequestHeader(value = "Authorization", required = false) String auth,
                                   @PathVariable long groupId, @RequestParam long afterId,
                                   @RequestParam(defaultValue = "50") int limit) {
        return groups.sync(auth, groupId, afterId, limit);
    }

    @GetMapping("/{groupId}/messages/search")
    public GroupDtos.Page search(@RequestHeader(value = "Authorization", required = false) String auth,
                                 @PathVariable long groupId, @RequestParam String q,
                                 @RequestParam(required = false) Long beforeId,
                                 @RequestParam(defaultValue = "20") int limit) {
        return groups.search(auth, groupId, q, beforeId, limit);
    }

    @GetMapping("/{groupId}/messages/{messageId}")
    public GroupMessage message(@RequestHeader(value = "Authorization", required = false) String auth,
                                @PathVariable long groupId, @PathVariable long messageId) {
        return groups.message(auth, groupId, messageId);
    }

    @PostMapping("/{groupId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@RequestHeader(value = "Authorization", required = false) String auth,
                     @PathVariable long groupId, @RequestBody GroupDtos.Read request) {
        groups.markRead(auth, groupId, request.lastReadMessageId());
    }
}
