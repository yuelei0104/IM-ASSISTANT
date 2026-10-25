package com.yuelei.imchat;

import com.yuelei.imchat.dao.GroupDao;
import com.yuelei.imchat.dao.GroupSummaryDao;
import com.yuelei.imchat.exception.ChatException;
import com.yuelei.imchat.model.GroupMessage;
import com.yuelei.imchat.model.GroupSummary;
import com.yuelei.imchat.service.GroupSummaryService;
import com.yuelei.imchat.service.SummaryGenerator;
import com.yuelei.imchat.service.UserAccessClient;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GroupSummaryServiceTest {
    private final GroupDao groups = mock(GroupDao.class);
    private final GroupSummaryDao summaries = mock(GroupSummaryDao.class);
    private final UserAccessClient access = mock(UserAccessClient.class);
    private final SummaryGenerator generator = mock(SummaryGenerator.class);
    private final GroupSummaryService service = new GroupSummaryService(groups, summaries, access, generator);

    @Test
    void outsidersCannotReadOrSendMessagesToTheModel() {
        when(access.requireUser("Bearer outsider")).thenReturn(23L);
        assertThrows(ChatException.class, () -> service.list("Bearer outsider", 4));
        assertThrows(ChatException.class, () -> service.generate("Bearer outsider", 4));
        verifyNoInteractions(generator, summaries);
        verify(groups, never()).history(anyLong(), anyLong(), any(), anyInt());
    }

    @Test
    void generateUsesOnlyVisibleMessagesAndSavesAGroupScopedSummary() {
        when(access.requireUser("Bearer member")).thenReturn(23L);
        when(groups.membership(4, 23)).thenReturn(new GroupDao.Membership(42, 42));
        var older = new GroupMessage(43, 4, 9, "old", "字幕内容", "CAPTION", LocalDateTime.now());
        var newer = new GroupMessage(51, 4, 10, "new", "后", LocalDateTime.now());
        when(groups.history(4, 42, null, 30)).thenReturn(List.of(newer, older));
        when(generator.summarize(List.of(older, newer))).thenReturn("群内结论");
        doAnswer(invocation -> {
            GroupSummaryDao.Draft draft = invocation.getArgument(0);
            assertEquals(4, draft.getGroupId());
            assertEquals(23, draft.getRequesterId());
            assertEquals(43, draft.getFirstMessageId());
            assertEquals(51, draft.getLastMessageId());
            draft.setId(7L);
            return 1;
        }).when(summaries).insert(any());
        var persisted = new GroupSummary(7, 4, 23, 43, 51, "群内结论", LocalDateTime.now());
        when(summaries.visibleById(7, 4, 23)).thenReturn(persisted);

        assertEquals(persisted, service.generate("Bearer member", 4));
        verify(groups).history(4, 42, null, 30);
        assertEquals(List.of(persisted), mockVisibleList(persisted));
    }

    private List<GroupSummary> mockVisibleList(GroupSummary persisted) {
        when(summaries.visibleList(4, 23, 20)).thenReturn(List.of(persisted));
        return service.list("Bearer member", 4);
    }

    @Test
    void emptyOrRevokedMembershipNeverCreatesASummary() {
        when(access.requireUser("Bearer member")).thenReturn(23L);
        when(groups.membership(4, 23)).thenReturn(new GroupDao.Membership(42, 42));
        assertThrows(ChatException.class, () -> service.generate("Bearer member", 4));
        verifyNoInteractions(generator, summaries);

        when(groups.history(4, 42, null, 30)).thenReturn(List.of(
                new GroupMessage(43, 4, 9, "id", "消息", LocalDateTime.now())));
        when(generator.summarize(any())).thenReturn("结果");
        when(groups.membership(4, 23)).thenReturn(new GroupDao.Membership(42, 42), null);
        assertThrows(ChatException.class, () -> service.generate("Bearer member", 4));
        verify(summaries, never()).insert(any());
    }

    @Test
    void rejoiningAfterModelCallCannotSaveSummaryOfEarlierCaptions() {
        when(access.requireUser("Bearer member")).thenReturn(23L);
        when(groups.membership(4, 23)).thenReturn(
                new GroupDao.Membership(42, 42), new GroupDao.Membership(50, 50));
        var caption = new GroupMessage(43, 4, 9, "caption", "入群前字幕", "CAPTION", LocalDateTime.now());
        when(groups.history(4, 42, null, 30)).thenReturn(List.of(caption));
        when(generator.summarize(List.of(caption))).thenReturn("敏感摘要");

        assertThrows(ChatException.class, () -> service.generate("Bearer member", 4));
        verify(summaries, never()).insert(any());
        verify(generator).summarize(List.of(caption));
    }

    @Test
    void revokedBetweenMembershipCheckAndInsertCannotSaveSummary() {
        when(access.requireUser("Bearer member")).thenReturn(23L);
        when(groups.membership(4, 23)).thenReturn(new GroupDao.Membership(42, 42));
        var caption = new GroupMessage(43, 4, 9, "caption", "字幕", "CAPTION", LocalDateTime.now());
        when(groups.history(4, 42, null, 30)).thenReturn(List.of(caption));
        when(generator.summarize(List.of(caption))).thenReturn("摘要");
        // The guarded INSERT returns 0 if membership changed after the Java check.
        when(summaries.insert(any())).thenReturn(0);

        ChatException error = assertThrows(ChatException.class, () -> service.generate("Bearer member", 4));
        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, error.status());
        verify(summaries, never()).visibleById(anyLong(), anyLong(), anyLong());
    }
}
