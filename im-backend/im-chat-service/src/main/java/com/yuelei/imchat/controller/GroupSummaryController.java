package com.yuelei.imchat.controller;

import com.yuelei.imchat.model.GroupSummary;
import com.yuelei.imchat.model.SummarySource;
import com.yuelei.imchat.service.GroupSummaryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/chats/groups/{groupId}/summaries")
public class GroupSummaryController {
    private final GroupSummaryService summaries;

    public GroupSummaryController(GroupSummaryService summaries) { this.summaries = summaries; }

    @GetMapping
    public ResponseEntity<List<GroupSummary>> list(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long groupId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(summaries.list(auth, groupId));
    }

    @PostMapping
    public ResponseEntity<GroupSummary> generate(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long groupId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(summaries.generate(auth, groupId));
    }

    @GetMapping("/{summaryId}/sources")
    public ResponseEntity<List<SummarySource>> sources(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long groupId, @PathVariable long summaryId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(summaries.sources(auth, groupId, summaryId));
    }
}
