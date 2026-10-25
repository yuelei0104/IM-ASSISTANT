package com.yuelei.imchat;

import com.yuelei.imchat.dao.NoticeDao;
import com.yuelei.imchat.service.NoticeService;
import com.yuelei.imchat.service.UserAccessClient;
import com.yuelei.imchat.exception.ChatException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NoticeServiceTest {
    private final NoticeDao dao = mock(NoticeDao.class);
    private final UserAccessClient access = mock(UserAccessClient.class);
    private final NoticeService service = new NoticeService(dao, access);

    @Test
    void inboxAndReadOperationsAreScopedToAuthenticatedUser() {
        when(access.requireUser("Bearer t")).thenReturn(10L);
        when(dao.list(10, null, 2)).thenReturn(List.of());
        when(dao.unread(10)).thenReturn(3L);
        assertEquals(3, service.list("Bearer t", null, 1).unreadCount());
        service.markRead("Bearer t", 4);
        service.markAllRead("Bearer t");
        verify(dao).markRead(10, 4);
        verify(dao).markAllRead(10);
        verify(dao).list(10, null, 2);
        verify(dao).unread(10);
    }

    @Test
    void keywordsArePrivateNormalizedAndReplacedAtomically() {
        when(access.requireUser("Bearer t")).thenReturn(10L);
        when(dao.keywords(10)).thenReturn(List.of("会议", "deadline"));
        assertEquals(List.of("会议", "deadline"), service.keywords("Bearer t").words());
        assertEquals(List.of("会议", "deadline"),
                service.replaceKeywords("Bearer t", List.of(" 会议 ", "DEADLINE")).words());
        var ordered = inOrder(dao);
        ordered.verify(dao).deleteKeywords(10);
        ordered.verify(dao).addKeywordWord(10, "会议");
        ordered.verify(dao).addKeywordWord(10, "deadline");
        assertThrows(ChatException.class,
                () -> service.replaceKeywords("Bearer t", List.of("会议", " 会议 ")));
        assertThrows(ChatException.class,
                () -> service.replaceKeywords("Bearer t", List.of("x")));
        verify(dao, times(1)).deleteKeywords(10);
    }
}
