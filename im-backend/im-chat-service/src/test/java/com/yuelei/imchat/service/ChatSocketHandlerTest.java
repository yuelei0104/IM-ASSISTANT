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
    private final ChatNodeDirectory directory = mock(ChatNodeDirectory.class);
    private final ChatSocketHandler handler = new ChatSocketHandler(access, directory);

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
        verify(directory).renew(42L);
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
        verifyNoInteractions(directory);
    }

    @Test
    void readReceiptsOnlyReachAuthenticatedPairDevices() throws Exception {
        var reader = mock(WebSocketSession.class);
        var sender = mock(WebSocketSession.class);
        var outsider = mock(WebSocketSession.class);
        when(reader.getId()).thenReturn("reader");
        when(sender.getId()).thenReturn("sender");
        when(outsider.getId()).thenReturn("outsider");
        when(reader.isOpen()).thenReturn(true);
        when(sender.isOpen()).thenReturn(true);
        when(outsider.isOpen()).thenReturn(true);
        when(access.requireUser("Bearer reader")).thenReturn(23L);
        when(access.requireUser("Bearer sender")).thenReturn(9L);
        handler.afterConnectionEstablished(reader);
        handler.afterConnectionEstablished(sender);
        handler.afterConnectionEstablished(outsider);
        handler.handleMessage(reader, new TextMessage("AUTH reader"));
        handler.handleMessage(sender, new TextMessage("AUTH sender"));

        handler.notifyRead(23, 9, 42);

        var readerMessages = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        var senderMessages = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(reader, times(2)).sendMessage(readerMessages.capture());
        verify(sender, times(2)).sendMessage(senderMessages.capture());
        assertEquals(List.of("READY", "READ 23 42"), readerMessages.getAllValues().stream().map(TextMessage::getPayload).toList());
        assertEquals(List.of("READY", "READ 23 42"), senderMessages.getAllValues().stream().map(TextMessage::getPayload).toList());
        verify(outsider, never()).sendMessage(any());
    }

    @Test
    void routeLivesUntilLastDeviceDisconnects() throws Exception {
        WebSocketSession first = mock(WebSocketSession.class);
        WebSocketSession second = mock(WebSocketSession.class);
        when(first.getId()).thenReturn("one");
        when(second.getId()).thenReturn("two");
        when(first.isOpen()).thenReturn(true);
        when(second.isOpen()).thenReturn(true);
        when(access.requireUser("Bearer user")).thenReturn(42L);
        handler.afterConnectionEstablished(first);
        handler.afterConnectionEstablished(second);
        handler.handleMessage(first, new TextMessage("AUTH user"));
        handler.handleMessage(second, new TextMessage("AUTH user"));
        verify(directory, times(2)).renew(42L);

        handler.afterConnectionClosed(first, CloseStatus.NORMAL);
        verify(directory, never()).remove(42L);
        handler.afterConnectionClosed(second, CloseStatus.NORMAL);
        verify(directory).remove(42L);
    }

    @Test
    void disconnectedDuringAuthenticationNeverRegistersRoute() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("gone");
        when(session.isOpen()).thenReturn(false);
        when(access.requireUser("Bearer user")).thenReturn(42L);
        handler.afterConnectionEstablished(session);
        handler.handleMessage(session, new TextMessage("AUTH user"));
        verifyNoInteractions(directory);
        verify(session, never()).sendMessage(any());
    }

    @Test
    void redisPresenceFailureDoesNotBlockAuthenticatedSocket() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("connected");
        when(session.isOpen()).thenReturn(true);
        when(access.requireUser("Bearer user")).thenReturn(42L);
        doThrow(new IllegalStateException("redis unavailable")).when(directory).renew(42L);
        handler.afterConnectionEstablished(session);
        handler.handleMessage(session, new TextMessage("AUTH user"));
        verify(session).sendMessage(argThat(message -> "READY".equals(message.getPayload())));
    }
}
