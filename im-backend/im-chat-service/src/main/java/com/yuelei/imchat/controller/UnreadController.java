package com.yuelei.imchat.controller;

import com.yuelei.imchat.model.UnreadCount;
import com.yuelei.imchat.service.DirectMessageService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chats/direct")
public class UnreadController {
    private final DirectMessageService service;

    public UnreadController(DirectMessageService service) { this.service = service; }

    public record ReadRequest(long lastReadMessageId) { }
    public record ReadReceipt(long lastReadMessageId) { }

    @GetMapping("/unread")
    public List<UnreadCount> unread(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.unread(authorization);
    }

    @PostMapping("/{peerId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @PathVariable long peerId, @RequestBody ReadRequest request) {
        service.markRead(authorization, peerId, request.lastReadMessageId());
    }

    @GetMapping("/{peerId}/receipt")
    public ReadReceipt receipt(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @PathVariable long peerId) {
        return new ReadReceipt(service.peerReadCursor(authorization, peerId));
    }
}
