package com.yuelei.imchat.service;

import com.yuelei.imchat.dao.GroupDao;
import com.yuelei.imchat.dao.GroupSummaryDao;
import com.yuelei.imchat.exception.ChatException;
import com.yuelei.imchat.model.GroupMessage;
import com.yuelei.imchat.model.GroupSummary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class GroupSummaryService {
    private final GroupDao groups;
    private final GroupSummaryDao summaries;
    private final UserAccessClient access;
    private final SummaryGenerator generator;

    public GroupSummaryService(GroupDao groups, GroupSummaryDao summaries,
                               UserAccessClient access, SummaryGenerator generator) {
        this.groups = groups;
        this.summaries = summaries;
        this.access = access;
        this.generator = generator;
    }

    public GroupSummary generate(String auth, long groupId) {
        long userId = access.requireUser(auth);
        var membership = member(groupId, userId);
        List<GroupMessage> messages = new ArrayList<>(groups.history(
                groupId, membership.joinedAfterId(), null, 30));
        if (messages.isEmpty()) throw new ChatException(HttpStatus.BAD_REQUEST, "群内还没有可总结的消息");
        Collections.reverse(messages);
        String content = generator.summarize(List.copyOf(messages));
        if (content == null || content.isBlank() || content.length() > 2000)
            throw new ChatException(HttpStatus.BAD_GATEWAY, "摘要内容无效");
        // Recheck access after the slow model call, before saving anything.
        var current = member(groupId, userId);
        if (messages.get(0).id() <= current.joinedAfterId())
            throw new ChatException(HttpStatus.FORBIDDEN, "无权总结加入前的消息");
        var draft = new GroupSummaryDao.Draft(groupId, userId, messages.get(0).id(),
                messages.get(messages.size() - 1).id(), content);
        summaries.insert(draft);
        if (draft.getId() == null) throw new ChatException(HttpStatus.INTERNAL_SERVER_ERROR, "摘要保存失败");
        GroupSummary saved = summaries.visibleById(draft.getId(), groupId, userId);
        if (saved == null) throw new ChatException(HttpStatus.INTERNAL_SERVER_ERROR, "摘要读取失败");
        return saved;
    }

    public List<GroupSummary> list(String auth, long groupId) {
        long userId = access.requireUser(auth);
        member(groupId, userId);
        return summaries.visibleList(groupId, userId, 20);
    }

    private GroupDao.Membership member(long groupId, long userId) {
        if (groupId <= 0) throw new ChatException(HttpStatus.BAD_REQUEST, "讨论室编号无效");
        var membership = groups.membership(groupId, userId);
        if (membership == null) throw new ChatException(HttpStatus.FORBIDDEN, "无权访问此讨论室");
        return membership;
    }
}
