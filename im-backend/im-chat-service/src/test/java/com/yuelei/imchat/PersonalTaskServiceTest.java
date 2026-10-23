package com.yuelei.imchat;

import com.yuelei.imchat.dao.PersonalTaskDao;
import com.yuelei.imchat.exception.ChatException;
import com.yuelei.imchat.model.PersonalTask;
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
    private final PersonalTaskService service = new PersonalTaskService(dao, access);

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
        var row = new PersonalTask(7, "任务", false, LocalDateTime.now());
        when(dao.owned(10, 7)).thenReturn(row);
        assertEquals(row, service.create("Bearer a", " 任务 "));
        assertEquals(10, captor.getValue().getOwnerId());
        assertEquals("任务", captor.getValue().getTitle());

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
}
