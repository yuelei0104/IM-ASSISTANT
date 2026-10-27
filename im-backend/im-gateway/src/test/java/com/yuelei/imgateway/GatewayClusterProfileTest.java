package com.yuelei.imgateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("cluster")
class GatewayClusterProfileTest {
    private static final AtomicBoolean nodeAHealthy = new AtomicBoolean(true);
    private static final HttpServer nodeA = startNode(nodeAHealthy);
    private static final HttpServer nodeB = startNode(new AtomicBoolean(true));

    @Autowired private RouteDefinitionLocator definitions;
    @Autowired private LoadBalancerClientFactory loadBalancers;

    @DynamicPropertySource
    static void chatNodes(DynamicPropertyRegistry properties) {
        properties.add("IM_CHAT_NODE_A_URL", () -> "http://127.0.0.1:" + nodeA.getAddress().getPort());
        properties.add("IM_CHAT_NODE_B_URL", () -> "http://127.0.0.1:" + nodeB.getAddress().getPort());
    }

    @AfterAll
    static void stopNodes() {
        nodeA.stop(0);
        nodeB.stop(0);
    }

    @Test
    void chatHttpAndWebSocketUseBothLocalInstances() {
        Map<String, URI> routes = definitions.getRouteDefinitions()
                .collectMap(RouteDefinition::getId, RouteDefinition::getUri)
                .block(Duration.ofSeconds(10));
        assertNotNull(routes);
        assertEquals(URI.create("lb://im-chat-service"), routes.get("chat-http"));
        assertEquals(URI.create("lb:ws://im-chat-service"), routes.get("chat-ws"));
        assertEquals(URI.create("http://127.0.0.1:8081"), routes.get("user-http"));
        ServiceInstanceListSupplier supplier = loadBalancers.getInstance("im-chat-service", ServiceInstanceListSupplier.class);
        assertNotNull(supplier, "Gateway's LoadBalancer must have a chat instance supplier");
        assertEquals(java.util.Set.of(nodeA.getAddress().getPort(), nodeB.getAddress().getPort()),
                supplier.get().next().block(Duration.ofSeconds(10)).stream()
                        .map(instance -> instance.getPort()).collect(java.util.stream.Collectors.toSet()));

        nodeAHealthy.set(false);
        try {
            Thread.sleep(3500); // Wait for the short health result cache to expire.
            assertEquals(java.util.Set.of(nodeB.getAddress().getPort()),
                    supplier.get().next().block(Duration.ofSeconds(10)).stream()
                            .map(instance -> instance.getPort()).collect(java.util.stream.Collectors.toSet()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail(exception);
        }
    }

    private static HttpServer startNode(AtomicBoolean healthy) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/actuator/health", exchange -> {
                byte[] body = (healthy.get() ? "UP" : "DOWN").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(healthy.get() ? 200 : 503, body.length);
                try (var stream = exchange.getResponseBody()) {
                    stream.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot open chat health test port", exception);
        }
    }
}
