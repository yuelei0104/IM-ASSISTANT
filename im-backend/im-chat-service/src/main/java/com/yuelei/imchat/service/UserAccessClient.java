package com.yuelei.imchat.service;

import com.yuelei.imchat.exception.ChatException;
import com.yuelei.imcommon.Identity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class UserAccessClient {
    private final RestClient client;

    public UserAccessClient(@Value("${im.user-service.url}") String url) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    public long requireUser(String authorization) {
        return lookup("/api/auth/identity", authorization);
    }

    public long requireFriend(String authorization, long friendId) {
        return lookup("/api/contacts/" + friendId + "/access", authorization);
    }

    private long lookup(String path, String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer "))
            throw new ChatException(HttpStatus.UNAUTHORIZED, "请先登录");
        try {
            Identity result = client.get().uri(path).header("Authorization", authorization)
                    .retrieve().body(Identity.class);
            if (result == null || result.userId() <= 0)
                throw new ChatException(HttpStatus.BAD_GATEWAY, "用户服务响应异常");
            return result.userId();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401)
                throw new ChatException(HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录");
            if (ex.getStatusCode().value() == 403)
                throw new ChatException(HttpStatus.FORBIDDEN, "只有好友之间可以发送消息");
            throw new ChatException(HttpStatus.BAD_GATEWAY, "用户服务暂不可用");
        } catch (ResourceAccessException ex) {
            throw new ChatException(HttpStatus.SERVICE_UNAVAILABLE, "无法连接用户服务，请确认 8081 端口已启动");
        }
    }

}
