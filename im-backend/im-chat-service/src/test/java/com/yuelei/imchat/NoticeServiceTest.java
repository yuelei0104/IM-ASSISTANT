package com.yuelei.imchat;

import com.yuelei.imchat.dao.NoticeDao;
import com.yuelei.imchat.service.NoticeService;
import com.yuelei.imchat.service.UserAccessClient;
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
}
