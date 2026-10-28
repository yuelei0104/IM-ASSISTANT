package com.yuelei.imchat.service;

import com.yuelei.imchat.config.ChatRealtimeConfig;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.TreeSet;

/** Fan out an ID-only notice once per active node, with a broadcast for missing routes. */
@Component
public class ChatRealtimePublisher {
    private final StringRedisTemplate redis;
    private final ChatNodeDirectory directory;

    public ChatRealtimePublisher(StringRedisTemplate redis, ChatNodeDirectory directory) {
        this.redis = redis;
        this.directory = directory;
    }

    public void publish(String payload) {
        var nodes = new TreeSet<String>();
        boolean missingRoute = false;
        for (long recipient : RealtimeEvent.recipients(payload)) {
            var routes = directory.activeNodes(recipient);
            if (routes.isEmpty()) {
                missingRoute = true;
            } else {
                nodes.addAll(routes);
            }
        }
        if (missingRoute) {
            // Query all recipients before falling back: an actual Redis failure
            // must propagate so Kafka does not acknowledge an unsent notice.
            redis.convertAndSend(ChatRealtimeConfig.TOPIC, payload);
            return;
        }
        for (String node : nodes)
            redis.convertAndSend(ChatRealtimeConfig.nodeTopic(node), payload);
    }
}
