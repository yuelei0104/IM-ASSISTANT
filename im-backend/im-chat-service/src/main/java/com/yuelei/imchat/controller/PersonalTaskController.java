package com.yuelei.imchat.controller;

import com.yuelei.imchat.model.PersonalTask;
import com.yuelei.imchat.service.PersonalTaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chats/tasks")
public class PersonalTaskController {
    private final PersonalTaskService tasks;

    public PersonalTaskController(PersonalTaskService tasks) { this.tasks = tasks; }

    public record Create(@NotBlank @Size(max = 100) String title) { }
    public record Complete(@NotNull Boolean completed) { }

    @GetMapping
    public ResponseEntity<PersonalTaskService.Page> list(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tasks.list(auth, beforeId, limit));
    }

    @PostMapping
    public ResponseEntity<PersonalTask> create(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @Valid @RequestBody Create request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(tasks.create(auth, request.title()));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<PersonalTask> complete(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id, @Valid @RequestBody Complete request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(tasks.complete(auth, id, request.completed()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader(value = "Authorization", required = false) String auth,
                       @PathVariable long id) {
        tasks.delete(auth, id);
    }
}
