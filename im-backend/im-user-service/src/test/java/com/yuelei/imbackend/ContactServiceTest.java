package com.yuelei.imbackend;

import com.yuelei.imbackend.dao.ContactDao;
import com.yuelei.imbackend.dao.UserDao;
import com.yuelei.imbackend.dto.ContactDtos.Invite;
import com.yuelei.imbackend.exception.AuthException;
import com.yuelei.imbackend.model.FriendRequestRow;
import com.yuelei.imbackend.model.UserAccount;
import com.yuelei.imbackend.model.ContactProfile;
import com.yuelei.imbackend.service.ContactService;
import com.yuelei.imbackend.service.SessionService;
import com.yuelei.imbackend.service.InvitationRateLimiter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;
import static org.mockito.Mockito.*;

class ContactServiceTest {
    private final ContactDao contacts = mock(ContactDao.class);
    private final UserDao users = mock(UserDao.class);
    private final SessionService sessions = mock(SessionService.class);
    private final InvitationRateLimiter inviteLimits = mock(InvitationRateLimiter.class);
    private final ContactService service = new ContactService(contacts, users, sessions, inviteLimits);

    @Test
    void chatAccessUsesTheSessionAndCurrentFriendship() {
        when(sessions.requireUserId("Bearer token")).thenReturn(25L);
        when(contacts.friendshipCount(10L, 25L)).thenReturn(1L);
        org.junit.jupiter.api.Assertions.assertEquals(25L, service.requireFriend("Bearer token", 10L));
        verify(contacts).friendshipCount(10L, 25L);
        assertThrows(AuthException.class, () -> service.requireFriend("Bearer token", 99L));
    }

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

        service.invite("Bearer token", new Invite("self_10", null, "hi"));
        verify(contacts, never()).invite(anyLong(), anyLong(), anyString());
    }

    @Test
    void invitationUsesTheBoundPhoneWithoutExposingIt() {
        when(sessions.requireUserId("Bearer token")).thenReturn(10L);
        when(users.byPhone("13800138000"))
                .thenReturn(new UserAccount(25L, "friend", "friend_25", "13800138000", null, "hash"));

        service.invite("Bearer token", new Invite(null, "138 0013 8000", "hi"));

        verify(users).byPhone("13800138000");
        verify(contacts).invite(10L, 25L, "hi");
    }

    @Test
    void invitationMustSpecifyExactlyOneIdentifier() {
        when(sessions.requireUserId("Bearer token")).thenReturn(10L);
        assertThrows(AuthException.class, () -> service.invite("Bearer token", new Invite(null, null, "hi")));
        assertThrows(AuthException.class, () -> service.invite("Bearer token", new Invite("user_25", "13800138000", "hi")));
        verifyNoInteractions(users, contacts);
    }

    @Test
    void unknownPhoneAndDuplicateInvitationHaveTheSameOutcome() {
        when(sessions.requireUserId("Bearer token")).thenReturn(10L);
        service.invite("Bearer token", new Invite(null, "13800138000", "hi"));
        when(users.byPhone("13800138000"))
                .thenReturn(new UserAccount(25L, "friend", "friend_25", "13800138000", null, "hash"));
        when(contacts.friendshipCount(10L, 25L)).thenReturn(1L);
        service.invite("Bearer token", new Invite(null, "13800138000", "hi"));
        verify(contacts, never()).invite(anyLong(), anyLong(), anyString());
        verify(inviteLimits, times(2)).check(10L);
    }

    @Test
    void friendsReturnAStableCursorAndProfileRequiresFriendship() {
        when(sessions.requireUserId("Bearer token")).thenReturn(10L);
        when(contacts.friends(10L, 0L, 2)).thenReturn(List.of(
                new ContactProfile(20L, "A", "user_20"), new ContactProfile(30L, "B", "user_30")));
        var page = service.friends("Bearer token", null, 1);
        assertEquals(20L, page.nextCursor());
        assertEquals(1, page.items().size());
        assertThrows(AuthException.class, () -> service.friendProfile("Bearer token", 99L));
    }
}
