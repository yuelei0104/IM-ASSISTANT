package com.yuelei.imgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import com.yuelei.imcluster.ChatNodeLoadBalancerConfiguration;

@SpringBootApplication
@LoadBalancerClient(value = "im-chat-service", configuration = ChatNodeLoadBalancerConfiguration.class)
public class ImGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ImGatewayApplication.class, args);
    }
}
