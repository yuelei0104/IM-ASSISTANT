package com.yuelei.imchat;

import com.yuelei.imchat.dao.MessageDao;
import com.yuelei.imchat.dto.MessageDtos.Send;
import com.yuelei.imchat.exception.ChatException;
import com.yuelei.imchat.model.DirectMessage;
import com.yuelei.imchat.service.DirectMessageService;
import com.yuelei.imchat.service.UserAccessClient;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DirectMessageServiceTest {
    private final MessageDao dao = mock(MessageDao.class);
    private final UserAccessClient access = mock(UserAccessClient.class);
    private final DirectMessageService service = new DirectMessageService(dao, access);
    private static final String ID = "e9873e73-1326-4329-a4c0-936912a48e37";

    @Test
    void sendChecksFriendshipAndReturnsPersistedMessage() {
        when(access.requireFriend("Bearer t", 9)).thenReturn(23L);
        var stored = new DirectMessage(42, 23, 9, ID, "你好", LocalDateTime.now());
        when(dao.byClientId(23, ID)).thenReturn(stored);

        assertEquals(stored, service.send("Bearer t", 9, new Send(ID, " 你好 ")));
        verify(dao).insert(9, 23, 23, 9, ID, "你好");
    }

    @Test
    void reusedIdForOtherContentOrRecipientIsRejected() {
        when(access.requireFriend("Bearer t", 9)).thenReturn(23L);
        when(dao.byClientId(23, ID))
                .thenReturn(new DirectMessage(42, 23, 9, ID, "原内容", LocalDateTime.now()));
        assertThrows(ChatException.class, () -> service.send("Bearer t", 9, new Send(ID, "新内容")));
    }

    @Test
    void historyIsRestrictedToAuthenticatedPairAndProvidesCursor() {
        when(access.requireUser("Bearer t")).thenReturn(23L);
        var older = new DirectMessage(11, 9, 23, ID, "早", LocalDateTime.now());
        var newer = new DirectMessage(12, 23, 9, ID, "晚", LocalDateTime.now());
        when(dao.history(9, 23, null, 2)).thenReturn(List.of(newer, older));

        var result = service.history("Bearer t", 9, null, 1);
        assertEquals(List.of(newer), result.messages());
        assertEquals(12L, result.nextBeforeId());
        assertThrows(ChatException.class, () -> service.history("Bearer t", 23, null, 50));
    }
}
