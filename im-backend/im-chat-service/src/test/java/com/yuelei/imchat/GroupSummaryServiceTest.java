package com.yuelei.imchat;

import com.yuelei.imchat.dao.GroupDao;
import com.yuelei.imchat.dao.GroupSummaryDao;
import com.yuelei.imchat.exception.ChatException;
import com.yuelei.imchat.model.GroupMessage;
import com.yuelei.imchat.model.GroupSummary;
import com.yuelei.imchat.model.SummarySource;
import com.yuelei.imchat.service.GroupSummaryService;
import com.yuelei.imchat.service.SummaryGenerator;
import com.yuelei.imchat.service.UserAccessClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

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

    @BeforeEach
    void groupExists() {
        when(groups.lockGroup(4)).thenReturn(new GroupDao.GroupRow(4, "讨论", 9, 0));
    }

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
            assertEquals(List.of(43L, 51L), draft.getMessageIds());
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

    @Test
    void recalledMessagesAreNeverSentToTheModelOrStoredInTheSummary() {
        when(access.requireUser("Bearer member")).thenReturn(23L);
        when(groups.membership(4, 23)).thenReturn(new GroupDao.Membership(42, 42));
        var recalled = new GroupMessage(44, 4, 9, "old", "", "TEXT", LocalDateTime.now(), LocalDateTime.now());
        var live = new GroupMessage(43, 4, 9, "live", "可用内容", "TEXT", LocalDateTime.now(), null);
        when(groups.history(4, 42, null, 30)).thenReturn(List.of(recalled, live));
        when(generator.summarize(List.of(live))).thenReturn("可用摘要");
        when(summaries.insert(any())).thenReturn(0);

        assertThrows(ChatException.class, () -> service.generate("Bearer member", 4));
        verify(generator).summarize(List.of(live));
        verify(summaries).insert(argThat(draft -> draft.getMessageIds().equals(List.of(43L))));
    }

    @Test
    void sourceMessagesRequireCurrentMembershipAndVisibleSummary() {
        when(access.requireUser("Bearer outsider")).thenReturn(30L);
        assertThrows(ChatException.class, () -> service.sources("Bearer outsider", 4, 7));
        verify(summaries, never()).visibleSources(anyLong(), anyLong(), anyLong());

        when(access.requireUser("Bearer member")).thenReturn(23L);
        when(groups.membership(4, 23)).thenReturn(new GroupDao.Membership(50, 50));
        assertThrows(ChatException.class, () -> service.sources("Bearer member", 4, 7));
        verify(summaries).visibleById(7, 4, 23);
        verify(summaries, never()).visibleSources(anyLong(), anyLong(), anyLong());
    }

    @Test
    void sourceQueryMustCheckMembershipAgainAfterSummaryLookup() {
        when(access.requireUser("Bearer member")).thenReturn(23L);
        when(groups.membership(4, 23)).thenReturn(new GroupDao.Membership(42, 42));
        when(summaries.visibleById(7, 4, 23)).thenReturn(
                new GroupSummary(7, 4, 23, 43, 51, "摘要", LocalDateTime.now()));
        var row = new SummarySource(43, 9, "实际讨论内容", "CAPTION", LocalDateTime.now());
        when(summaries.visibleSources(7, 4, 23)).thenReturn(List.of(row));
        assertEquals(List.of(row), service.sources("Bearer member", 4, 7));

        // Simulates a membership change or recall between the two SQL statements.
        when(summaries.visibleSources(7, 4, 23)).thenReturn(List.of());
        assertThrows(ChatException.class, () -> service.sources("Bearer member", 4, 7));
    }
}
