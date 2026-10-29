package com.yuelei.imchat.controller;

import com.yuelei.imchat.dto.MessageDtos.Page;
import com.yuelei.imchat.dto.MessageDtos.Send;
import com.yuelei.imchat.dto.MessageDtos.SyncPage;
import com.yuelei.imchat.model.DirectMessage;
import com.yuelei.imchat.service.DirectMessageService;
import com.yuelei.imchat.service.UserAccessClient;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/chats/direct/{friendId}/messages")
public class DirectMessageController {
    private final DirectMessageService service;
    private final UserAccessClient access;

    public DirectMessageController(DirectMessageService service, UserAccessClient access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping
    public DirectMessage send(@RequestHeader(value = "Authorization", required = false) String authorization,
                              @PathVariable long friendId, @Valid @RequestBody Send input) {
        if (friendId > 0) access.requireFriend(authorization, friendId);
        return service.send(authorization, friendId, input);
    }

    @DeleteMapping("/{messageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recall(@RequestHeader(value = "Authorization", required = false) String authorization,
                       @PathVariable long friendId, @PathVariable long messageId) {
        service.recall(authorization, friendId, messageId);
    }

    @GetMapping("/recalls")
    public com.yuelei.imchat.dto.MessageDtos.RecallPage recalls(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long friendId, @RequestParam(defaultValue = "0") long afterId,
            @RequestParam(defaultValue = "50") int limit) {
        return service.recalls(authorization, friendId, afterId, limit);
    }

    @GetMapping
    public Page history(@RequestHeader(value = "Authorization", required = false) String authorization,
                        @PathVariable long friendId,
                        @RequestParam(required = false) Long beforeId,
                        @RequestParam(defaultValue = "50") int limit) {
        return service.history(authorization, friendId, beforeId, limit);
    }

    @GetMapping("/sync")
    public SyncPage sync(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long friendId,
            @RequestParam long afterId,
            @RequestParam(defaultValue = "50") int limit) {
        return service.sync(authorization, friendId, afterId, limit);
    }

    @GetMapping("/search")
    public Page search(@RequestHeader(value = "Authorization", required = false) String authorization,
                       @PathVariable long friendId, @RequestParam String q,
                       @RequestParam(required = false) Long beforeId,
                       @RequestParam(defaultValue = "20") int limit) {
        return service.search(authorization, friendId, q, beforeId, limit);
    }
}
