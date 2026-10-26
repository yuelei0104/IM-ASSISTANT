package com.yuelei.imgateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("cluster")
class GatewayClusterProfileTest {
    @Autowired private RouteDefinitionLocator definitions;
    @Autowired private DiscoveryClient discovery;

    @Test
    void chatHttpAndWebSocketUseBothLocalInstances() {
        Map<String, URI> routes = definitions.getRouteDefinitions()
                .collectMap(RouteDefinition::getId, RouteDefinition::getUri)
                .block(Duration.ofSeconds(10));
        assertNotNull(routes);
        assertEquals(URI.create("lb://im-chat-service"), routes.get("chat-http"));
        assertEquals(URI.create("lb:ws://im-chat-service"), routes.get("chat-ws"));
        assertEquals(URI.create("http://127.0.0.1:8081"), routes.get("user-http"));
        assertEquals(java.util.Set.of(8082, 8083), discovery.getInstances("im-chat-service")
                .stream().map(instance -> instance.getPort()).collect(java.util.stream.Collectors.toSet()));
    }
}
