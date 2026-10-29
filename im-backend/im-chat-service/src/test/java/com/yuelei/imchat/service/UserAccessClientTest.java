package com.yuelei.imchat.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserAccessClientTest {
    @Test
    void reusesValidatedIdentityAndFriendshipOnlyWithinOneHttpRequest() throws Exception {
        AtomicInteger identityChecks = new AtomicInteger();
        AtomicInteger friendChecks = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/auth/identity", exchange -> {
            identityChecks.incrementAndGet();
            reply(exchange);
        });
        server.createContext("/api/contacts/7/access", exchange -> {
            friendChecks.incrementAndGet();
            reply(exchange);
        });
        server.start();
        try {
            var access = new UserAccessClient("http://127.0.0.1:" + server.getAddress().getPort());
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
            assertEquals(42L, access.requireUser("Bearer session-a"));
            assertEquals(42L, access.requireUser("Bearer session-a"));
            access.prefetchFriends("Bearer session-a", List.of(7L));
            assertEquals(42L, access.requireFriend("Bearer session-a", 7));
            assertEquals(1, identityChecks.get());
            assertEquals(1, friendChecks.get());

            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
            assertEquals(42L, access.requireUser("Bearer session-a"));
            assertEquals(2, identityChecks.get()); // No cross-request revocation delay.
        } finally {
            RequestContextHolder.resetRequestAttributes();
            server.stop(0);
        }
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        byte[] json = "{\"userId\":42}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, json.length);
        try (var output = exchange.getResponseBody()) { output.write(json); }
    }
}
