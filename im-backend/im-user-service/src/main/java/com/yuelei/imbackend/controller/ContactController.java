package com.yuelei.imbackend.controller;

import com.yuelei.imbackend.dto.ContactDtos.Invite;
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

    @GetMapping("/users/{handle}")
    public ContactProfile find(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @PathVariable String handle) {
        return contacts.find(authorization, handle);
    }

    @GetMapping("/users/phone/{phone}")
    public ContactProfile findByPhone(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable String phone) {
        return contacts.findByPhone(authorization, phone);
    }

    @GetMapping
    public List<ContactProfile> friends(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return contacts.friends(authorization);
    }

    @GetMapping("/requests")
    public List<FriendRequestView> requests(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return contacts.requests(authorization);
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
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
