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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class UserAccessClient {
    private final RestClient client;
    private record RequestResult(String authorization, long userId) { }

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

    /** Preflight the friend checks before opening a database transaction. */
    public void prefetchFriends(String authorization, java.util.List<Long> friendIds) {
        if (friendIds == null || friendIds.size() > 20) return;
        for (Long friendId : friendIds)
            if (friendId != null && friendId > 0) requireFriend(authorization, friendId);
    }

    private long lookup(String path, String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer "))
            throw new ChatException(HttpStatus.UNAUTHORIZED, "请先登录");
        // One request may check the identity or the same friendship more than once.
        // The value never survives this HTTP request, so revocation is not delayed.
        var attributes = RequestContextHolder.getRequestAttributes();
        var request = attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
        String attributeName = UserAccessClient.class.getName() + path;
        if (request != null && request.getAttribute(attributeName) instanceof RequestResult cached
                && cached.authorization().equals(authorization)) return cached.userId();
        try {
            Identity result = client.get().uri(path).header("Authorization", authorization)
                    .retrieve().body(Identity.class);
            if (result == null || result.userId() <= 0)
                throw new ChatException(HttpStatus.BAD_GATEWAY, "用户服务响应异常");
            if (request != null) request.setAttribute(attributeName, new RequestResult(authorization, result.userId()));
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
