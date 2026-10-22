package com.yuelei.imbackend;

import com.yuelei.imbackend.dao.ContactDao;
import com.yuelei.imbackend.dao.UserDao;
import com.yuelei.imbackend.dto.ContactDtos.Invite;
import com.yuelei.imbackend.exception.AuthException;
import com.yuelei.imbackend.model.FriendRequestRow;
import com.yuelei.imbackend.model.UserAccount;
import com.yuelei.imbackend.service.ContactService;
import com.yuelei.imbackend.service.SessionService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ContactServiceTest {
    private final ContactDao contacts = mock(ContactDao.class);
    private final UserDao users = mock(UserDao.class);
    private final SessionService sessions = mock(SessionService.class);
    private final ContactService service = new ContactService(contacts, users, sessions);

    @Test
    void acceptingAnIncomingRequestCreatesOneCanonicalFriendship() {
        when(sessions.requireUserId("Bearer token")).thenReturn(10L);
        when(contacts.requestForUpdate(7L))
                .thenReturn(new FriendRequestRow(7L, 25L, 10L, "PENDING"));
        when(contacts.updateRequest(7L, "ACCEPTED")).thenReturn(1);

        service.resolve("Bearer token", 7L, "ACCEPTED");

        var sequence = inOrder(contacts);
        sequence.verify(contacts).requestForUpdate(7L);
        sequence.verify(contacts).addFriendship(10L, 25L);
        sequence.verify(contacts).updateRequest(7L, "ACCEPTED");
    }

    @Test
    void anotherUserCannotAcceptOrWithdrawTheRequest() {
        when(sessions.requireUserId("Bearer token")).thenReturn(99L);
        when(contacts.requestForUpdate(7L))
                .thenReturn(new FriendRequestRow(7L, 25L, 10L, "PENDING"));

        assertThrows(AuthException.class, () -> service.resolve("Bearer token", 7L, "ACCEPTED"));
        assertThrows(AuthException.class, () -> service.resolve("Bearer token", 7L, "WITHDRAWN"));
        verify(contacts, never()).addFriendship(anyLong(), anyLong());
        verify(contacts, never()).updateRequest(anyLong(), anyString());
    }

    @Test
    void anAccountCannotInviteItself() {
        when(sessions.requireUserId("Bearer token")).thenReturn(10L);
        when(users.byHandle("self_10"))
                .thenReturn(new UserAccount(10L, "self", "self_10", null, "self@example.com", "hash"));

        assertThrows(AuthException.class, () -> service.invite("Bearer token", new Invite("self_10", "hi")));
        verify(contacts, never()).invite(anyLong(), anyLong(), anyString());
    }
}
