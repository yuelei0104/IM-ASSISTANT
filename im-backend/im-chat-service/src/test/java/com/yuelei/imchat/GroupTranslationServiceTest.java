package com.yuelei.imchat;

import com.yuelei.imchat.dao.GroupDao;
import com.yuelei.imchat.exception.ChatException;
import com.yuelei.imchat.model.GroupMessage;
import com.yuelei.imchat.service.GroupTranslationService;
import com.yuelei.imchat.service.TranslationGenerator;
import com.yuelei.imchat.service.UserAccessClient;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GroupTranslationServiceTest {
    private final GroupDao groups = mock(GroupDao.class);
    private final UserAccessClient access = mock(UserAccessClient.class);
    private final TranslationGenerator model = mock(TranslationGenerator.class);
    private final GroupTranslationService service = new GroupTranslationService(groups, access, model);

    @Test
    void outsiderAndPreJoinMessageNeverReachTheModel() {
        when(access.requireUser("Bearer a")).thenReturn(9L);
        ChatException outsider = assertThrows(ChatException.class,
                () -> service.preview("Bearer a", 4, 13, "en"));
        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, outsider.status());
        when(groups.membership(4, 9)).thenReturn(new GroupDao.Membership(14, 14));
        assertThrows(ChatException.class, () -> service.preview("Bearer a", 4, 13, "en"));
        verify(groups).messageById(4, 13, 14);
        verifyNoInteractions(model);
    }

    @Test
    void validSourceReturnsPreviewWithoutWritingMessages() {
        when(access.requireUser("Bearer a")).thenReturn(9L);
        when(groups.membership(4, 9)).thenReturn(new GroupDao.Membership(0, 0));
        var source = new GroupMessage(13, 4, 8, "uuid", "明天开会 at 9", LocalDateTime.now());
        when(groups.messageById(4, 13, 0)).thenReturn(source);
        when(model.translate(source.body(), "en")).thenReturn("Meeting tomorrow at 9");
        assertEquals(new GroupTranslationService.Preview(13, "en", "Meeting tomorrow at 9"),
                service.preview("Bearer a", 4, 13, "en"));
        verify(groups, times(2)).messageById(4, 13, 0);
        verify(groups, never()).insertMessage(anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void recallOrRemovalDuringModelCallRevokesPreview() {
        when(access.requireUser("Bearer a")).thenReturn(9L);
        when(groups.membership(4, 9)).thenReturn(new GroupDao.Membership(0, 0), null);
        when(groups.messageById(4, 13, 0)).thenReturn(
                new GroupMessage(13, 4, 8, "uuid", "敏感消息", LocalDateTime.now()));
        when(model.translate("敏感消息", "en")).thenReturn("Sensitive message");
        assertThrows(ChatException.class, () -> service.preview("Bearer a", 4, 13, "en"));
        verify(groups, times(1)).messageById(4, 13, 0);
    }

    @Test
    void invalidTargetOrRecalledSourceIsRejected() {
        when(access.requireUser("Bearer a")).thenReturn(9L);
        assertThrows(ChatException.class, () -> service.preview("Bearer a", 4, 13, "fr"));
        when(groups.membership(4, 9)).thenReturn(new GroupDao.Membership(0, 0));
        when(groups.messageById(4, 13, 0)).thenReturn(new GroupMessage(
                13, 4, 8, "uuid", "", "TEXT", LocalDateTime.now(), LocalDateTime.now()));
        assertThrows(ChatException.class, () -> service.preview("Bearer a", 4, 13, "zh"));
        verifyNoInteractions(model);
    }
}
