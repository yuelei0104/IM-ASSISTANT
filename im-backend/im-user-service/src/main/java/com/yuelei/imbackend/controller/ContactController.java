package com.yuelei.imbackend.controller;

import com.yuelei.imbackend.dto.ContactDtos.Invite;
import com.yuelei.imbackend.dto.ContactPage;
import com.yuelei.imbackend.model.ContactProfile;
import com.yuelei.imbackend.model.FriendRequestView;
import com.yuelei.imbackend.service.ContactService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/contacts")
public class ContactController {
    private final ContactService contacts;

    public ContactController(ContactService contacts) { this.contacts = contacts; }

    @GetMapping
    public ContactPage<ContactProfile> friends(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @RequestParam(required = false) Long afterId,
                                                @RequestParam(defaultValue = "50") int limit) {
        return contacts.friends(authorization, afterId, limit);
    }

    public record ChatAccess(long userId) { }

    @GetMapping("/{friendId}/profile")
    public ContactProfile profile(@RequestHeader(value = "Authorization", required = false) String authorization,
                                  @PathVariable long friendId) {
        return contacts.friendProfile(authorization, friendId);
    }

    @GetMapping("/{friendId}/access")
    public ChatAccess access(@RequestHeader(value = "Authorization", required = false) String authorization,
                             @PathVariable long friendId) {
        return new ChatAccess(contacts.requireFriend(authorization, friendId));
    }

    @GetMapping("/requests")
    public ContactPage<FriendRequestView> requests(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                    @RequestParam(required = false) Long beforeId,
                                                    @RequestParam(defaultValue = "50") int limit) {
        return contacts.requests(authorization, beforeId, limit);
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void invite(@RequestHeader(value = "Authorization", required = false) String authorization,
                       @Valid @RequestBody Invite input) {
        contacts.invite(authorization, input);
    }

    @PostMapping("/requests/{id}/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void accept(@RequestHeader(value = "Authorization", required = false) String authorization,
                       @PathVariable long id) { contacts.resolve(authorization, id, "ACCEPTED"); }

    @PostMapping("/requests/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@RequestHeader(value = "Authorization", required = false) String authorization,
                       @PathVariable long id) { contacts.resolve(authorization, id, "REJECTED"); }

    @PostMapping("/requests/{id}/withdraw")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @PathVariable long id) { contacts.resolve(authorization, id, "WITHDRAWN"); }

    @DeleteMapping("/{friendId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@RequestHeader(value = "Authorization", required = false) String authorization,
                       @PathVariable long friendId) { contacts.remove(authorization, friendId); }
}
