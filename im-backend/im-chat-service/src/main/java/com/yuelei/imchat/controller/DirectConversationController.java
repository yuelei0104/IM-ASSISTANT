package com.yuelei.imchat.controller;

import com.yuelei.imchat.service.DirectMessageService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chats/direct/conversations")
public class DirectConversationController {
    private final DirectMessageService service;

    public DirectConversationController(DirectMessageService service) { this.service = service; }

    @GetMapping("/hidden")
    public List<Long> hidden(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.hiddenConversations(authorization);
    }

    @DeleteMapping("/{peerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@RequestHeader(value = "Authorization", required = false) String authorization,
                      @PathVariable long peerId) {
        service.clearConversation(authorization, peerId);
    }
}
