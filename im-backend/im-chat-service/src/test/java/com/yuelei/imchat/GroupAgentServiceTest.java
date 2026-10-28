package com.yuelei.imchat;

import com.yuelei.imchat.dao.GroupDao;
import com.yuelei.imchat.exception.ChatException;
import com.yuelei.imchat.model.GroupMessage;
import com.yuelei.imchat.service.GroupAgentEngine;
import com.yuelei.imchat.service.GroupAgentService;
import com.yuelei.imchat.service.UserAccessClient;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroupAgentServiceTest {
    private final GroupDao groups = mock(GroupDao.class);
    private final UserAccessClient access = mock(UserAccessClient.class);
    private final GroupAgentEngine engine = mock(GroupAgentEngine.class);
    private final GroupAgentService service = new GroupAgentService(groups, access, engine);

    private static GroupMessage message(long id, String body) {
        return new GroupMessage(id, 4, 9, "m" + id, body, "TEXT", LocalDateTime.now());
    }

    @Test
    void outsiderNeverSendsGroupTextToEmbeddingOrChatModel() {
        when(access.requireUser("outsider")).thenReturn(23L);
        assertThrows(ChatException.class, () -> service.ask("outsider", 4, "会议结论是什么"));
        verifyNoInteractions(engine);
        verify(groups, never()).history(anyLong(), anyLong(), any(), anyInt());
    }

    @Test
    void agentSeesOnlyMessagesAfterTheJoinCursorAndReturnsVerifiedSources() {
        when(access.requireUser("member")).thenReturn(23L);
        when(groups.membership(4, 23)).thenReturn(new GroupDao.Membership(42, 42));
        var visible = message(43, "周一交付方案");
        when(groups.history(4, 42, null, 30)).thenReturn(List.of(visible));
        when(engine.answer(eq(4L), eq("什么时候交付"), anyList()))
                .thenReturn(new GroupAgentEngine.Draft("建议周一交付。", List.of(43L)));
        when(groups.messageById(4, 43, 42)).thenReturn(visible);
        var answer = service.ask("member", 4, "什么时候交付");
        assertEquals("建议周一交付。", answer.answer());
        assertEquals(List.of(new GroupAgentService.Source(43, "周一交付方案")), answer.sources());
        verify(engine).answer(4, "什么时候交付", List.of(new GroupAgentEngine.Candidate(43, "周一交付方案")));
    }

    @Test
    void revokedOrRejoinedMemberGetsNoResultAfterSlowModelCall() {
        when(access.requireUser("member")).thenReturn(23L);
        when(groups.membership(4, 23)).thenReturn(new GroupDao.Membership(42, 42), null);
        when(groups.history(4, 42, null, 30)).thenReturn(List.of(message(43, "私密内容")));
        when(engine.answer(eq(4L), anyString(), anyList()))
                .thenReturn(new GroupAgentEngine.Draft("私密内容", List.of(43L)));
        assertThrows(ChatException.class, () -> service.ask("member", 4, "讨论了什么"));
        verify(groups, never()).messageById(anyLong(), anyLong(), anyLong());

        reset(groups);
        when(groups.membership(4, 23)).thenReturn(
                new GroupDao.Membership(42, 42), new GroupDao.Membership(50, 50));
        when(groups.history(4, 42, null, 30)).thenReturn(List.of(message(43, "私密内容")));
        assertThrows(ChatException.class, () -> service.ask("member", 4, "讨论了什么"));
        verify(groups).messageById(4, 43, 50);
    }

    @Test
    void recalledOrInventedSourcesAreNotReturned() {
        when(access.requireUser("member")).thenReturn(23L);
        when(groups.membership(4, 23)).thenReturn(new GroupDao.Membership(42, 42));
        when(groups.history(4, 42, null, 30)).thenReturn(List.of(message(43, "需要撤回")));
        when(engine.answer(eq(4L), anyString(), anyList()))
                .thenReturn(new GroupAgentEngine.Draft("结论", List.of(43L)));
        assertThrows(ChatException.class, () -> service.ask("member", 4, "讨论了什么"));
        when(engine.answer(eq(4L), anyString(), anyList()))
                .thenReturn(new GroupAgentEngine.Draft("结论", List.of(41L)));
        assertThrows(ChatException.class, () -> service.ask("member", 4, "讨论了什么"));
    }

    @Test
    void noEvidenceReturnsNoIrrelevantCitations() {
        when(access.requireUser("member")).thenReturn(23L);
        when(groups.membership(4, 23)).thenReturn(new GroupDao.Membership(42, 42));
        when(groups.history(4, 42, null, 30)).thenReturn(List.of(message(43, "好的")));
        when(engine.answer(eq(4L), anyString(), anyList()))
                .thenReturn(new GroupAgentEngine.Draft(GroupAgentEngine.NO_EVIDENCE, List.of()));
        var result = service.ask("member", 4, "下次会议要做什么");
        assertEquals(GroupAgentEngine.NO_EVIDENCE, result.answer());
        assertTrue(result.sources().isEmpty());
    }

    @Test
    void recalledContextInvalidatesAnAnswerEvenIfTheModelDidNotCiteIt() {
        when(access.requireUser("member")).thenReturn(23L);
        when(groups.membership(4, 23)).thenReturn(new GroupDao.Membership(42, 42));
        var cited = message(43, "周一交设计稿");
        when(groups.history(4, 42, null, 30)).thenReturn(List.of(cited, message(44, "敏感资料")));
        when(engine.answer(eq(4L), anyString(), anyList())).thenReturn(
                new GroupAgentEngine.Draft("周一交设计稿。", List.of(43L), List.of(43L, 44L)));
        when(groups.messageById(4, 43, 42)).thenReturn(cited);
        assertThrows(ChatException.class, () -> service.ask("member", 4, "何时交稿"));
    }
}
