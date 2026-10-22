package com.yuelei.imchat.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatSocketHandlerTest {
    private final UserAccessClient access = mock(UserAccessClient.class);
    private final ChatSocketHandler handler = new ChatSocketHandler(access);

    @AfterEach
    void close() { handler.shutdown(); }

    @Test
    void onlyAuthenticatedRecipientGetsTheMessageNotification() throws Exception {
        WebSocketSession recipient = mock(WebSocketSession.class);
        WebSocketSession stranger = mock(WebSocketSession.class);
        when(recipient.getId()).thenReturn("recipient");
        when(stranger.getId()).thenReturn("stranger");
        when(recipient.isOpen()).thenReturn(true);
        when(stranger.isOpen()).thenReturn(true);
        when(access.requireUser("Bearer valid-token")).thenReturn(42L);
        handler.afterConnectionEstablished(recipient);
        handler.afterConnectionEstablished(stranger);

        handler.handleMessage(recipient, new TextMessage("AUTH valid-token"));
        handler.notifyMessage(91L, 7L, 42L);

        var messages = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(recipient, times(2)).sendMessage(messages.capture());
        assertEquals(List.of("READY", "MSG 91 7 42"),
                messages.getAllValues().stream().map(TextMessage::getPayload).toList());
        verify(stranger, never()).sendMessage(any());
    }

    @Test
    void rejectsInvalidAuthentication() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("untrusted");
        handler.afterConnectionEstablished(session);
        handler.handleMessage(session, new TextMessage("HELLO"));
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verifyNoInteractions(access);
    }
}
