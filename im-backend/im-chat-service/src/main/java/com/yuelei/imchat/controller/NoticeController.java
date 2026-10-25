package com.yuelei.imchat.controller;

import com.yuelei.imchat.service.NoticeService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@RestController
@RequestMapping("/api/chats/notices")
public class NoticeController {
    private final NoticeService notices;
    public NoticeController(NoticeService notices) { this.notices = notices; }
    public record KeywordsRequest(@NotNull List<String> words) { }

    @GetMapping("/keywords")
    public NoticeService.Keywords keywords(@RequestHeader(value = "Authorization", required = false) String auth) {
        return notices.keywords(auth);
    }

    @PutMapping("/keywords")
    public NoticeService.Keywords replaceKeywords(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @Valid @RequestBody KeywordsRequest input) {
        return notices.replaceKeywords(auth, input.words());
    }

    @GetMapping
    public NoticeService.Page list(@RequestHeader(value = "Authorization", required = false) String auth,
                                   @RequestParam(required = false) Long beforeId,
                                   @RequestParam(defaultValue = "50") int limit) {
        return notices.list(auth, beforeId, limit);
    }

    @PostMapping("/{noticeId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@RequestHeader(value = "Authorization", required = false) String auth,
                     @PathVariable long noticeId) {
        notices.markRead(auth, noticeId);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readAll(@RequestHeader(value = "Authorization", required = false) String auth) {
        notices.markAllRead(auth);
    }
}
