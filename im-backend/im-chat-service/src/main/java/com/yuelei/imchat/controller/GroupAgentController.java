package com.yuelei.imchat.controller;

import com.yuelei.imchat.service.GroupAgentService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chats/groups/{groupId}/agent")
public class GroupAgentController {
    private final GroupAgentService agent;

    public GroupAgentController(GroupAgentService agent) { this.agent = agent; }

    public record Ask(String question) { }

    @PostMapping("/ask")
    public ResponseEntity<GroupAgentService.Answer> ask(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long groupId, @RequestBody Ask request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(agent.ask(auth, groupId, request.question()));
    }
}
