package com.yuelei.imchat.controller;

import com.yuelei.imchat.service.GroupTranslationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chats/groups/{groupId}/messages/{messageId}/translation")
public class GroupTranslationController {
    private final GroupTranslationService translations;

    public GroupTranslationController(GroupTranslationService translations) {
        this.translations = translations;
    }

    public record Request(@NotBlank String targetLanguage) { }

    @PostMapping
    public ResponseEntity<GroupTranslationService.Preview> preview(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long groupId, @PathVariable long messageId,
            @Valid @RequestBody Request request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(translations.preview(auth, groupId, messageId, request.targetLanguage()));
    }
}
