package com.yuelei.imchat;

import com.yuelei.imchat.dao.PersonalTaskDao;
import com.yuelei.imchat.dao.GroupDao;
import com.yuelei.imchat.exception.ChatException;
import com.yuelei.imchat.model.PersonalTask;
import com.yuelei.imchat.model.GroupMessage;
import com.yuelei.imchat.service.PersonalTaskService;
import com.yuelei.imchat.service.UserAccessClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersonalTaskServiceTest {
    private final PersonalTaskDao dao = mock(PersonalTaskDao.class);
    private final UserAccessClient access = mock(UserAccessClient.class);
    private final GroupDao groups = mock(GroupDao.class);
    private final PersonalTaskService service = new PersonalTaskService(dao, access, groups);

    @Test
    void eachOperationUsesOnlyAuthenticatedOwner() {
        when(access.requireUser("Bearer a")).thenReturn(10L);
        when(dao.list(10, null, 3)).thenReturn(List.of());
        service.list("Bearer a", null, 2);
        verify(dao).list(10, null, 3);

        var captor = ArgumentCaptor.forClass(PersonalTaskDao.Draft.class);
        when(dao.insert(captor.capture())).thenAnswer(invocation -> {
            captor.getValue().setId(7L);
            return 1;
        });
        var row = new PersonalTask(7, "任务", false, LocalDateTime.now(), null, null);
        when(dao.owned(10, 7)).thenReturn(row);
        assertEquals(row, service.create("Bearer a", " 任务 ", null, null));
        assertEquals(10, captor.getValue().getOwnerId());
        assertEquals("任务", captor.getValue().getTitle());
        assertNull(captor.getValue().getSourceGroupId());

        service.complete("Bearer a", 7, false);
        verify(dao).setCompleted(10, 7, false);
        when(dao.delete(10, 7)).thenReturn(1);
        service.delete("Bearer a", 7);
        verify(dao).delete(10, 7);
    }

    @Test
    void anotherUsersTaskCannotBeChangedOrDeleted() {
        when(access.requireUser("Bearer b")).thenReturn(20L);
        assertThrows(ChatException.class, () -> service.complete("Bearer b", 7, true));
        assertThrows(ChatException.class, () -> service.delete("Bearer b", 7));
        verify(dao, never()).setCompleted(anyLong(), anyLong(), anyBoolean());
        verify(dao, never()).delete(eq(10L), anyLong());
    }

    @Test
    void groupSourceRequiresCurrentMembershipVisibleAndLiveMessage() {
        when(access.requireUser("Bearer a")).thenReturn(10L);
        when(groups.lockGroup(4)).thenReturn(new GroupDao.GroupRow(4, "项目", 9, 0));
        assertThrows(ChatException.class, () -> service.create("Bearer a", "任务", 4L, 8L));
        verify(dao, never()).insert(any());

        when(groups.membership(4, 10)).thenReturn(new GroupDao.Membership(9, 9));
        assertThrows(ChatException.class, () -> service.create("Bearer a", "任务", 4L, 8L));
        verify(groups).messageById(4, 8, 9);
        verify(dao, never()).insert(any());

        assertThrows(ChatException.class, () -> service.create("Bearer a", "任务", 4L, null));
        verify(dao, never()).insert(any());
    }

    @Test
    void savesOnlyExplicitlyConfirmedTitleAndSourceIdentifiers() {
        when(access.requireUser("Bearer a")).thenReturn(10L);
        when(groups.lockGroup(4)).thenReturn(new GroupDao.GroupRow(4, "项目", 9, 0));
        when(groups.membership(4, 10)).thenReturn(new GroupDao.Membership(2, 2));
        when(groups.messageById(4, 8, 2)).thenReturn(new GroupMessage(
                8, 4, 9, "message-id", "群内较长的原文", LocalDateTime.now()));
        var draft = ArgumentCaptor.forClass(PersonalTaskDao.Draft.class);
        when(dao.insert(draft.capture())).thenAnswer(invocation -> {
            draft.getValue().setId(7L);
            return 1;
        });
        var saved = new PersonalTask(7, "用户确认的标题", false, LocalDateTime.now(), 4L, 8L);
        when(dao.owned(10, 7)).thenReturn(saved);
        assertEquals(saved, service.create("Bearer a", " 用户确认的标题 ", 4L, 8L));
        assertEquals("用户确认的标题", draft.getValue().getTitle());
        assertEquals(4L, draft.getValue().getSourceGroupId());
        assertEquals(8L, draft.getValue().getSourceMessageId());
    }
}
