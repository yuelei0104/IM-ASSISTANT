package com.yuelei.imchat.controller;

import com.yuelei.imchat.dto.MessageDtos.Page;
import com.yuelei.imchat.dto.MessageDtos.Send;
import com.yuelei.imchat.model.DirectMessage;
import com.yuelei.imchat.service.DirectMessageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chats/direct/{friendId}/messages")
public class DirectMessageController {
    private final DirectMessageService service;

    public DirectMessageController(DirectMessageService service) { this.service = service; }

    @PostMapping
    public DirectMessage send(@RequestHeader(value = "Authorization", required = false) String authorization,
                              @PathVariable long friendId, @Valid @RequestBody Send input) {
        return service.send(authorization, friendId, input);
    }

    @GetMapping
    public Page history(@RequestHeader(value = "Authorization", required = false) String authorization,
                        @PathVariable long friendId,
                        @RequestParam(required = false) Long beforeId,
                        @RequestParam(defaultValue = "50") int limit) {
        return service.history(authorization, friendId, beforeId, limit);
    }
}
