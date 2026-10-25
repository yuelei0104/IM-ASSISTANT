package com.yuelei.imchat;

import com.yuelei.imchat.dao.GroupDao;
import com.yuelei.imchat.dao.GroupSummaryDao;
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
import org.springframework.http.HttpStatus;

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
    private final GroupSummaryDao summaries = mock(GroupSummaryDao.class);
    private final GroupService service = new GroupService(dao, access, events, notices, summaries);
    private static final String ID = "e9873e73-1326-4329-a4c0-936912a48e37";

    @Test
    void ownerTransferRequiresCurrentMemberAndAllowsOriginalOwnerToLeave() {
        when(access.requireUser("Bearer owner")).thenReturn(9L);
        when(dao.lockGroup(4)).thenReturn(new GroupDao.GroupRow(4, "讨论", 9, 0),
                new GroupDao.GroupRow(4, "讨论", 9, 0),
                new GroupDao.GroupRow(4, "讨论", 9, 0),
                new GroupDao.GroupRow(4, "讨论", 10, 0),
                new GroupDao.GroupRow(4, "讨论", 10, 0));
        when(dao.memberIds(4)).thenReturn(List.of(9L, 10L));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ChatException.class,
                () -> service.transferOwner("Bearer owner", 4, 9)).status());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ChatException.class,
                () -> service.transferOwner("Bearer owner", 4, 11)).status());
        verify(dao, never()).transferOwner(anyLong(), anyLong());

        when(dao.membership(4, 10)).thenReturn(new GroupDao.Membership(0, 0));
        when(dao.transferOwner(4, 10)).thenReturn(1);
        when(dao.group(4)).thenReturn(new GroupDao.GroupRow(4, "讨论", 10, 0));
        assertEquals(10, service.transferOwner("Bearer owner", 4, 10).ownerId());
        verify(events).publishEvent(new GroupEvent(4, 0, List.of(9L, 10L)));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ChatException.class,
                () -> service.transferOwner("Bearer owner", 4, 10)).status());

        when(dao.removeMember(4, 9)).thenReturn(1);
        when(dao.memberIds(4)).thenReturn(List.of(10L));
        service.leave("Bearer owner", 4);
        verify(dao).removeMember(4, 9);
    }

    @Test
    void dissolveDeletesDependentRowsInOrderAndNotifiesOnlyAfterSuccess() {
        when(access.requireUser("Bearer owner")).thenReturn(9L);
        when(access.requireUser("Bearer member")).thenReturn(10L);
        when(dao.lockGroup(4)).thenReturn(new GroupDao.GroupRow(4, "讨论", 9, 0));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ChatException.class,
                () -> service.dissolve("Bearer member", 4)).status());
        verifyNoInteractions(notices, summaries);
        when(dao.memberIds(4)).thenReturn(List.of(9L, 10L));
        when(dao.deleteGroup(4)).thenReturn(1);
        service.dissolve("Bearer owner", 4);
        var ordered = inOrder(notices, summaries, dao, events);
        ordered.verify(dao, times(2)).lockGroup(4);
        ordered.verify(dao).memberIds(4);
        ordered.verify(notices).deleteForGroup(4);
        ordered.verify(summaries).deleteForGroup(4);
        ordered.verify(dao).deleteRecalls(4);
        ordered.verify(dao).deleteMessages(4);
        ordered.verify(dao).deleteMembers(4);
        ordered.verify(dao).deleteGroup(4);
        ordered.verify(events).publishEvent(new GroupEvent(4, 0, List.of(9L, 10L)));
    }

    @Test
    void leavingOrRemovalRevokesMembershipAndNotifiesFormerMember() {
        when(dao.lockGroup(4)).thenReturn(new GroupDao.GroupRow(4, "讨论", 9, 0));
        when(dao.removeMember(4, 10)).thenReturn(1);
        when(dao.memberIds(4)).thenReturn(List.of(9L, 11L));

        when(access.requireUser("Bearer member")).thenReturn(10L);
        service.leave("Bearer member", 4);
        verify(events).publishEvent(new GroupEvent(4, 0, List.of(9L, 11L, 10L)));

        when(access.requireUser("Bearer owner")).thenReturn(9L);
        service.remove("Bearer owner", 4, 10);
        verify(dao, times(2)).removeMember(4, 10);
        verify(events, times(2)).publishEvent(new GroupEvent(4, 0, List.of(9L, 11L, 10L)));
    }

    @Test
    void ownerCannotLeaveAndNonOwnerCannotRemoveOtherMembers() {
        when(dao.lockGroup(4)).thenReturn(new GroupDao.GroupRow(4, "讨论", 9, 0));
        when(access.requireUser("Bearer owner")).thenReturn(9L);
        when(access.requireUser("Bearer member")).thenReturn(10L);
        assertThrows(ChatException.class, () -> service.leave("Bearer owner", 4));
        assertThrows(ChatException.class, () -> service.remove("Bearer member", 4, 11));
        assertThrows(ChatException.class, () -> service.remove("Bearer owner", 4, 9));
        when(dao.removeMember(4, 11)).thenReturn(0);
        assertThrows(ChatException.class, () -> service.remove("Bearer owner", 4, 11));
        verifyNoInteractions(events);
    }

    @Test
    void onlyOwnerCanInviteAndExistingMembersCannotBeInvitedAgain() {
        when(access.requireUser("Bearer t")).thenReturn(7L);
        when(dao.group(4)).thenReturn(new GroupDao.GroupRow(4, "讨论", 9, 0));
        assertThrows(ChatException.class, () ->
                service.invite("Bearer t", 4, new GroupDtos.Invite(List.of(10L))));
        verify(access, never()).requireFriend(any(), anyLong());

        when(access.requireUser("Bearer t")).thenReturn(9L);
        when(dao.lockGroup(4)).thenReturn(new GroupDao.GroupRow(4, "讨论", 9, 0));
        when(dao.memberIds(4)).thenReturn(List.of(9L, 10L));
        when(access.requireFriend("Bearer t", 10L)).thenReturn(9L);
        assertThrows(ChatException.class, () ->
                service.invite("Bearer t", 4, new GroupDtos.Invite(List.of(10L))));
        verify(dao, never()).addMember(anyLong(), anyLong(), anyLong());
    }

    @Test
    void invitationRechecksOwnerAfterTheGroupLock() {
        when(access.requireUser("Bearer owner")).thenReturn(9L);
        when(access.requireFriend("Bearer owner", 10L)).thenReturn(9L);
        when(dao.group(4)).thenReturn(new GroupDao.GroupRow(4, "讨论", 9, 0));
        when(dao.lockGroup(4)).thenReturn(new GroupDao.GroupRow(4, "讨论", 11, 0));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ChatException.class,
                () -> service.invite("Bearer owner", 4, new GroupDtos.Invite(List.of(10L)))).status());
        verify(dao, never()).addMember(anyLong(), anyLong(), anyLong());
    }

    @Test
    void newMembersStartAfterLatestMessageAndCannotReadEarlierHistory() {
        when(access.requireUser("Bearer t")).thenReturn(9L);
        when(dao.group(4)).thenReturn(new GroupDao.GroupRow(4, "讨论", 9, 0));
        when(dao.lockGroup(4)).thenReturn(new GroupDao.GroupRow(4, "讨论", 9, 0));
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
        verify(dao, never()).insertMessage(anyLong(), anyLong(), any(), any(), any());

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
        verify(dao, never()).insertMessage(anyLong(), anyLong(), any(), any(), any());

        var saved = new GroupMessage(51, 4, 9, ID, "@伙伴#10 请看", LocalDateTime.now());
        when(dao.byClientId(9, ID)).thenReturn(null, saved);
        assertEquals(saved, service.send("Bearer t", 4, input));
        verify(notices).add(4, 51, 9, 10);
        verify(events).publishEvent(new MentionEvent(List.of(10L)));
        verify(events).publishEvent(new GroupEvent(4, 51, List.of(9L, 10L)));

        when(notices.recipients(51)).thenReturn(List.of(10L));
        assertEquals(saved, service.send("Bearer t", 4, input));
        verify(notices, times(1)).add(4, 51, 9, 10);
        verify(dao, times(1)).insertMessage(4, 9, ID, "@伙伴#10 请看", "TEXT");
        verifyNoMoreInteractions(events);
    }

    @Test
    void captionsRequireMembershipAndCannotReuseTextMessageId() {
        when(access.requireUser("Bearer t")).thenReturn(9L);
        when(dao.lockGroup(4)).thenReturn(new GroupDao.GroupRow(4, "讨论", 9, 0));
        var caption = new GroupDtos.SendGroup(ID, "会议字幕", List.of(), "CAPTION");
        assertThrows(ChatException.class, () -> service.send("Bearer t", 4, caption));
        verify(dao, never()).insertMessage(anyLong(), anyLong(), any(), any(), any());

        when(dao.membership(4, 9)).thenReturn(new GroupDao.Membership(0, 0));
        when(dao.memberIds(4)).thenReturn(List.of(9L, 10L));
        assertThrows(ChatException.class, () -> service.send("Bearer t", 4,
                new GroupDtos.SendGroup(ID, "会议字幕", List.of(10L), "CAPTION")));
        var textMessage = new GroupMessage(51, 4, 9, ID, "会议字幕", LocalDateTime.now());
        when(dao.byClientId(9, ID)).thenReturn(textMessage);
        assertThrows(ChatException.class, () -> service.send("Bearer t", 4, caption));
        verify(dao, never()).insertMessage(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void recallRequiresCurrentMemberAndSenderAndRemovesMentionAndSummary() {
        when(access.requireUser("Bearer owner")).thenReturn(9L);
        when(access.requireUser("Bearer member")).thenReturn(10L);
        when(dao.lockGroup(4)).thenReturn(new GroupDao.GroupRow(4, "讨论", 9, 0));
        var message = new GroupMessage(51, 4, 9, ID, "提醒内容", LocalDateTime.now());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ChatException.class,
                () -> service.recall("Bearer owner", 4, 51)).status());
        when(dao.membership(4, 9)).thenReturn(new GroupDao.Membership(0, 0));
        when(dao.membership(4, 10)).thenReturn(new GroupDao.Membership(0, 0));
        when(dao.messageById(4, 51, 0)).thenReturn(message);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ChatException.class,
                () -> service.recall("Bearer member", 4, 51)).status());
        when(dao.recall(4, 51, 9)).thenReturn(1);
        when(dao.memberIds(4)).thenReturn(List.of(9L, 10L));
        when(notices.recipients(51)).thenReturn(List.of(10L));
        service.recall("Bearer owner", 4, 51);
        verify(dao).addRecall(4, 51);
        verify(notices).deleteForMessage(51);
        verify(summaries).deleteContaining(4, 51);
        verify(events).publishEvent(new GroupEvent(4, 51, List.of(9L, 10L)));
        verify(events).publishEvent(new MentionEvent(List.of(10L)));

        when(dao.messageById(4, 51, 0)).thenReturn(new GroupMessage(51, 4, 9, ID, "", "TEXT",
                message.createdAt(), LocalDateTime.now()));
        service.recall("Bearer owner", 4, 51);
        verify(dao, times(1)).addRecall(4, 51);
    }

    @Test
    void recallFeedRejectsFormerMembersAndPrejoinMessages() {
        when(access.requireUser("Bearer member")).thenReturn(10L);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ChatException.class,
                () -> service.recalls("Bearer member", 4, 0, 50)).status());
        when(dao.membership(4, 10)).thenReturn(new GroupDao.Membership(42, 42));
        service.recalls("Bearer member", 4, 3, 50);
        verify(dao).recalls(4, 42, 3, 51);
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
