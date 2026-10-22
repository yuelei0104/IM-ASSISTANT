package com.yuelei.imchat;

import com.yuelei.imchat.dao.MessageDao;
import com.yuelei.imchat.dto.MessageDtos.Send;
import com.yuelei.imchat.exception.ChatException;
import com.yuelei.imchat.model.DirectMessage;
import com.yuelei.imchat.model.UnreadCount;
import com.yuelei.imchat.service.DirectMessageService;
import com.yuelei.imchat.service.UserAccessClient;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DirectMessageServiceTest {
    private final MessageDao dao = mock(MessageDao.class);
    private final UserAccessClient access = mock(UserAccessClient.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final DirectMessageService service = new DirectMessageService(dao, access, events);
    private static final String ID = "e9873e73-1326-4329-a4c0-936912a48e37";

    @Test
    void sendChecksFriendshipAndReturnsPersistedMessage() {
        when(access.requireFriend("Bearer t", 9)).thenReturn(23L);
        var stored = new DirectMessage(42, 23, 9, ID, "你好", LocalDateTime.now());
        when(dao.byClientId(23, ID)).thenReturn(stored);

        assertEquals(stored, service.send("Bearer t", 9, new Send(ID, " 你好 ")));
        verify(dao).insert(9, 23, 23, 9, ID, "你好");
        verify(dao).trackInbox(9, 23);
        verify(events).publishEvent(stored);
    }

    @Test
    void reusedIdForOtherContentOrRecipientIsRejected() {
        when(access.requireFriend("Bearer t", 9)).thenReturn(23L);
        when(dao.byClientId(23, ID))
                .thenReturn(new DirectMessage(42, 23, 9, ID, "原内容", LocalDateTime.now()));
        assertThrows(ChatException.class, () -> service.send("Bearer t", 9, new Send(ID, "新内容")));
        verifyNoInteractions(events);
    }

    @Test
    void historyIsRestrictedToAuthenticatedPairAndProvidesCursor() {
        when(access.requireUser("Bearer t")).thenReturn(23L);
        var older = new DirectMessage(11, 9, 23, ID, "早", LocalDateTime.now());
        var newer = new DirectMessage(12, 23, 9, ID, "晚", LocalDateTime.now());
        when(dao.history(9, 23, null, 2)).thenReturn(List.of(newer, older));

        var result = service.history("Bearer t", 9, null, 1);
        assertEquals(List.of(newer), result.messages());
        assertEquals(12L, result.nextBeforeId());
        assertThrows(ChatException.class, () -> service.history("Bearer t", 23, null, 50));
    }

    @Test
    void unreadCountsAreScopedToTheSessionAndOnlyReceivedMessagesCanBeMarkedRead() {
        when(access.requireUser("Bearer t")).thenReturn(23L);
        when(dao.unreadCounts(23L)).thenReturn(List.of(new UnreadCount(9, 2)));
        when(dao.receivedCount(23L, 9L, 42L)).thenReturn(1L);

        assertEquals(List.of(new UnreadCount(9, 2)), service.unread("Bearer t"));
        service.markRead("Bearer t", 9L, 42L);
        verify(dao).markRead(23L, 9L, 42L);

        assertThrows(ChatException.class, () -> service.markRead("Bearer t", 9L, 99L));
        assertThrows(ChatException.class, () -> service.markRead("Bearer t", 23L, 42L));
        verify(dao, never()).markRead(23L, 9L, 99L);
    }
}
