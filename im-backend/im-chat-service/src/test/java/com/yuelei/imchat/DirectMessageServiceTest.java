package com.yuelei.imchat;

import com.yuelei.imchat.dao.MessageDao;
import com.yuelei.imchat.dto.MessageDtos.Send;
import com.yuelei.imchat.exception.ChatException;
import com.yuelei.imchat.model.DirectMessage;
import com.yuelei.imchat.model.UnreadCount;
import com.yuelei.imchat.model.MessageRecall;
import org.springframework.http.HttpStatus;
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
        var ordered = inOrder(dao);
        ordered.verify(dao).ensurePair(9, 23);
        ordered.verify(dao).lockPair(9, 23);
        ordered.verify(dao).insert(9, 23, 23, 9, ID, "你好");
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
    void syncPaginatesForwardWithinTheAuthenticatedPair() {
        when(access.requireUser("Bearer t")).thenReturn(23L);
        var first = new DirectMessage(12, 9, 23, ID, "一", LocalDateTime.now());
        var second = new DirectMessage(13, 23, 9, ID, "二", LocalDateTime.now());
        when(dao.after(9, 23, 11, 2)).thenReturn(List.of(first, second));
        var result = service.sync("Bearer t", 9, 11, 1);
        assertEquals(List.of(first), result.messages());
        assertEquals(12L, result.nextAfterId());
        verify(dao).after(9, 23, 11, 2);
        assertThrows(ChatException.class, () -> service.sync("Bearer t", 23, 11, 1));
        assertThrows(ChatException.class, () -> service.sync("Bearer t", 9, -1, 1));
        assertThrows(ChatException.class, () -> service.sync("Bearer t", 9, 11, 51));
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
    @Test
    void searchIsScopedToAuthenticatedPairAndEscapesLikeWildcards() {
        when(access.requireUser("Bearer t")).thenReturn(23L);
        var newer = new DirectMessage(12, 23, 9, ID, "100%_完成", LocalDateTime.now());
        var older = new DirectMessage(11, 9, 23, ID, "100%_回顾", LocalDateTime.now());
        when(dao.search(9, 23, "100=%=_", null, 2)).thenReturn(List.of(newer, older));
        var page = service.search("Bearer t", 9, " 100%_ ", null, 1);
        assertEquals(List.of(newer), page.messages());
        assertEquals(12L, page.nextBeforeId());
        verify(dao).search(9, 23, "100=%=_", null, 2);
        assertThrows(ChatException.class, () -> service.search("Bearer t", 23, "关键词", null, 20));
        assertThrows(ChatException.class, () -> service.search("Bearer t", 9, "%", null, 20));
        verify(dao, times(1)).search(anyLong(), anyLong(), any(), any(), anyInt());
    }

    @Test
    void recallChecksSenderAndTimeWindowAndPublishesTheTombstone() {
        when(access.requireUser("Bearer t")).thenReturn(23L);
        var sent = new DirectMessage(42, 23, 9, ID, "私密内容", LocalDateTime.now());
        var tombstone = new DirectMessage(42, 23, 9, ID, "", sent.createdAt(), LocalDateTime.now());
        when(dao.byId(42)).thenReturn(sent, tombstone, tombstone);
        when(dao.recall(42, 23)).thenReturn(1);
        service.recall("Bearer t", 9, 42);
        service.recall("Bearer t", 9, 42);
        verify(dao, times(1)).recall(42, 23);
        verify(dao).addRecall(42, 9, 23);
        verify(events).publishEvent(tombstone);

        when(dao.byId(43)).thenReturn(new DirectMessage(43, 9, 23, ID, "别人发送", LocalDateTime.now()));
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ChatException.class,
                () -> service.recall("Bearer t", 9, 43)).status());
        when(dao.byId(44)).thenReturn(new DirectMessage(44, 23, 9, ID, "超时", LocalDateTime.now().minusMinutes(3)));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ChatException.class,
                () -> service.recall("Bearer t", 9, 44)).status());
        verify(dao, never()).addRecall(eq(44L), anyLong(), anyLong());
    }

    @Test
    void recallFeedIsScopedToAuthenticatedPairAndPaginates() {
        when(access.requireUser("Bearer t")).thenReturn(23L);
        var first = new MessageRecall(8, 42, LocalDateTime.now());
        var next = new MessageRecall(9, 43, LocalDateTime.now());
        when(dao.recalls(9, 23, 5, 2)).thenReturn(List.of(first, next));
        var page = service.recalls("Bearer t", 9, 5, 1);
        assertEquals(List.of(first), page.items());
        assertEquals(8L, page.nextAfterId());
        assertThrows(ChatException.class, () -> service.recalls("Bearer t", 23, 0, 50));
    }
}
