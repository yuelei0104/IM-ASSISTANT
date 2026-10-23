package com.yuelei.imchat;

import com.yuelei.imchat.dao.GroupDao;
import com.yuelei.imchat.dao.NoticeDao;
import com.yuelei.imchat.dto.GroupDtos;
import com.yuelei.imchat.exception.ChatException;
import com.yuelei.imchat.model.GroupMessage;
import com.yuelei.imchat.service.GroupEvent;
import com.yuelei.imchat.service.GroupService;
import com.yuelei.imchat.service.MentionEvent;
import com.yuelei.imchat.service.UserAccessClient;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroupServiceTest {
    private final GroupDao dao = mock(GroupDao.class);
    private final UserAccessClient access = mock(UserAccessClient.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final NoticeDao notices = mock(NoticeDao.class);
    private final GroupService service = new GroupService(dao, access, events, notices);
    private static final String ID = "e9873e73-1326-4329-a4c0-936912a48e37";

    @Test
    void onlyOwnerCanInviteAndExistingMembersCannotBeInvitedAgain() {
        when(access.requireUser("Bearer t")).thenReturn(7L);
        when(dao.group(4)).thenReturn(new GroupDao.GroupRow(4, "讨论", 9, 0));
        assertThrows(ChatException.class, () ->
                service.invite("Bearer t", 4, new GroupDtos.Invite(List.of(10L))));
        verify(access, never()).requireFriend(any(), anyLong());

        when(access.requireUser("Bearer t")).thenReturn(9L);
        when(dao.memberIds(4)).thenReturn(List.of(9L, 10L));
        when(access.requireFriend("Bearer t", 10L)).thenReturn(9L);
        assertThrows(ChatException.class, () ->
                service.invite("Bearer t", 4, new GroupDtos.Invite(List.of(10L))));
        verify(dao, never()).addMember(anyLong(), anyLong(), anyLong());
    }

    @Test
    void newMembersStartAfterLatestMessageAndCannotReadEarlierHistory() {
        when(access.requireUser("Bearer t")).thenReturn(9L);
        when(dao.group(4)).thenReturn(new GroupDao.GroupRow(4, "讨论", 9, 0));
        when(dao.memberIds(4)).thenReturn(List.of(9L), List.of(9L, 10L));
        when(dao.latestId(4)).thenReturn(42L);
        when(access.requireFriend("Bearer t", 10L)).thenReturn(9L);
        service.invite("Bearer t", 4, new GroupDtos.Invite(List.of(10L)));
        verify(dao).addMember(4, 10, 42);
        verify(events).publishEvent(new GroupEvent(4, 0, List.of(9L, 10L)));

        when(access.requireUser("Bearer member")).thenReturn(10L);
        when(dao.membership(4, 10)).thenReturn(new GroupDao.Membership(42, 42));
        service.history("Bearer member", 4, null, 50);
        verify(dao).history(4, 42, null, 51);
        when(dao.after(4, 42, 40, 2)).thenReturn(List.of(
                new GroupMessage(43, 4, 9, ID, "新消息", LocalDateTime.now()),
                new GroupMessage(44, 4, 9, ID, "后续", LocalDateTime.now())));
        var recovered = service.sync("Bearer member", 4, 40, 1);
        assertEquals(List.of(43L), recovered.messages().stream().map(GroupMessage::id).toList());
        assertEquals(43L, recovered.nextAfterId());
        verify(dao).after(4, 42, 40, 2);
        assertThrows(ChatException.class, () -> service.sync("Bearer member", 4, -1, 1));
        assertThrows(ChatException.class, () -> service.markRead("Bearer member", 4, 40));
        verify(dao, never()).validMessage(4, 40, 42);
        when(dao.validMessage(4, 43, 42)).thenReturn(null);
        assertThrows(ChatException.class, () -> service.markRead("Bearer member", 4, 43));
        verify(dao, never()).markRead(anyLong(), anyLong(), anyLong());
    }

    @Test
    void messagesRequireMembershipAndClientIdCannotChangeContent() {
        when(access.requireUser("Bearer t")).thenReturn(9L);
        when(dao.lockGroup(4)).thenReturn(new GroupDao.GroupRow(4, "讨论", 9, 0));
        assertThrows(ChatException.class, () ->
                service.send("Bearer t", 4, new GroupDtos.SendGroup(ID, "你好", List.of())));
        verify(dao, never()).insertMessage(anyLong(), anyLong(), any(), any());

        when(dao.membership(4, 9)).thenReturn(new GroupDao.Membership(0, 0));
        when(dao.byClientId(9, ID)).thenReturn(
                new GroupMessage(51, 4, 9, ID, "另一条消息", LocalDateTime.now()));
        assertThrows(ChatException.class, () ->
                service.send("Bearer t", 4, new GroupDtos.SendGroup(ID, "你好", List.of())));
        verifyNoInteractions(events);
    }

    @Test
    void mentionsAreRestrictedToMembersAndOnlyInsertedOnceForRetries() {
        when(access.requireUser("Bearer t")).thenReturn(9L);
        when(dao.lockGroup(4)).thenReturn(new GroupDao.GroupRow(4, "讨论", 9, 0));
        when(dao.membership(4, 9)).thenReturn(new GroupDao.Membership(0, 0));
        when(dao.memberIds(4)).thenReturn(List.of(9L, 10L));
        var input = new GroupDtos.SendGroup(ID, "@伙伴#10 请看", List.of(10L));
        assertThrows(ChatException.class, () ->
                service.send("Bearer t", 4, new GroupDtos.SendGroup(ID, "请看", List.of(11L))));
        verify(dao, never()).insertMessage(anyLong(), anyLong(), any(), any());

        var saved = new GroupMessage(51, 4, 9, ID, "@伙伴#10 请看", LocalDateTime.now());
        when(dao.byClientId(9, ID)).thenReturn(null, saved);
        assertEquals(saved, service.send("Bearer t", 4, input));
        verify(notices).add(4, 51, 9, 10);
        verify(events).publishEvent(new MentionEvent(List.of(10L)));
        verify(events).publishEvent(new GroupEvent(4, 51, List.of(9L, 10L)));

        when(notices.recipients(51)).thenReturn(List.of(10L));
        assertEquals(saved, service.send("Bearer t", 4, input));
        verify(notices, times(1)).add(4, 51, 9, 10);
        verify(dao, times(1)).insertMessage(4, 9, ID, "@伙伴#10 请看");
        verifyNoMoreInteractions(events);
    }
    @Test
    void searchRequiresMembershipAndPassesJoinCursorToSql() {
        when(access.requireUser("Bearer member")).thenReturn(10L);
        assertThrows(ChatException.class, () -> service.search("Bearer member", 4, "会议", null, 20));
        verify(dao, never()).search(anyLong(), anyLong(), any(), any(), anyInt());

        when(dao.membership(4, 10)).thenReturn(new GroupDao.Membership(42, 42));
        var message = new GroupMessage(43, 4, 9, ID, "会议纪要", LocalDateTime.now());
        when(dao.search(4, 42, "会议", null, 2)).thenReturn(List.of(message));
        var result = service.search("Bearer member", 4, "会议", null, 1);
        assertEquals(List.of(message), result.messages());
        assertNull(result.nextBeforeId());
        verify(dao).search(4, 42, "会议", null, 2);
        assertThrows(ChatException.class, () -> service.search("Bearer member", 4, "会议", -1L, 20));
    }
}
