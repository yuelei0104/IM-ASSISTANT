package com.yuelei.imcluster;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

// Only imported into the im-chat-service LoadBalancer child context. Keep this
// outside the gateway application's component scan to avoid affecting other routes.
@Configuration(proxyBeanMethods = false)
@Profile("cluster")
public class ChatNodeLoadBalancerConfiguration {
    private static final String SERVICE = "im-chat-service";

    @Bean
    ServiceInstanceListSupplier chatNodeInstances(
            @Value("${IM_CHAT_NODE_A_URL:http://127.0.0.1:8082}") URI nodeA,
            @Value("${IM_CHAT_NODE_B_URL:http://127.0.0.1:8083}") URI nodeB) {
        List<ServiceInstance> nodes = List.of(instance("chat-a", nodeA), instance("chat-b", nodeB));
        WebClient client = WebClient.create();

        // Probe both nodes without routing the health requests back through the
        // load balancer. Reuse each result briefly so a chat request does not
        // trigger a pair of HTTP health requests on every call.
        Mono<List<ServiceInstance>> healthy = Mono.defer(() -> Flux.fromIterable(nodes)
                .filterWhen(node -> client.get()
                        .uri(node.getUri().resolve("/actuator/health"))
                        .exchangeToMono(response -> Mono.just(response.statusCode().is2xxSuccessful()))
                        .timeout(Duration.ofSeconds(2))
                        .onErrorReturn(false))
                .collectList()).cache(Duration.ofSeconds(3));

        return new ServiceInstanceListSupplier() {
            @Override
            public String getServiceId() {
                return SERVICE;
            }

            @Override
            public Flux<List<ServiceInstance>> get() {
                return healthy.flux();
            }
        };
    }

    private static ServiceInstance instance(String id, URI uri) {
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getPort() < 1
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))) {
            throw new IllegalArgumentException("Chat node URL must be an absolute HTTP(S) host and port: " + uri);
        }
        return new DefaultServiceInstance(id, SERVICE, uri.getHost(), uri.getPort(), "https".equals(uri.getScheme()));
    }
}
