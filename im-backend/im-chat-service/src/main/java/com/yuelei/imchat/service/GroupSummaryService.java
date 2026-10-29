package com.yuelei.imchat.service;

import com.yuelei.imchat.dao.GroupDao;
import com.yuelei.imchat.dao.GroupSummaryDao;
import com.yuelei.imchat.exception.ChatException;
import com.yuelei.imchat.model.GroupMessage;
import com.yuelei.imchat.model.GroupSummary;
import com.yuelei.imchat.model.SummarySource;
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
    private final GroupSummaryWriter writer;

    public GroupSummaryService(GroupDao groups, GroupSummaryDao summaries,
                               UserAccessClient access, SummaryGenerator generator,
                               GroupSummaryWriter writer) {
        this.groups = groups;
        this.summaries = summaries;
        this.access = access;
        this.generator = generator;
        this.writer = writer;
    }

    public GroupSummary generate(String auth, long groupId) {
        long userId = access.requireUser(auth);
        var membership = member(groupId, userId);
        List<GroupMessage> messages = new ArrayList<>(groups.history(
                groupId, membership.joinedAfterId(), null, 30));
        messages.removeIf(message -> message.recalledAt() != null);
        if (messages.isEmpty()) throw new ChatException(HttpStatus.BAD_REQUEST, "群内还没有可总结的消息");
        Collections.reverse(messages);
        String content = generator.summarize(List.copyOf(messages));
        if (content == null || content.isBlank() || content.length() > 2000)
            throw new ChatException(HttpStatus.BAD_GATEWAY, "摘要内容无效");
        // Inference runs outside the transaction. The writer obtains the group lock,
        // rechecks current membership and writes the guarded summary in a short transaction.
        return writer.save(groupId, userId, List.copyOf(messages), content);
    }

    public List<GroupSummary> list(String auth, long groupId) {
        long userId = access.requireUser(auth);
        member(groupId, userId);
        return summaries.visibleList(groupId, userId, 20);
    }

    public List<SummarySource> sources(String auth, long groupId, long summaryId) {
        long userId = access.requireUser(auth);
        member(groupId, userId);
        if (summaryId <= 0 || summaries.visibleById(summaryId, groupId, userId) == null)
            throw new ChatException(HttpStatus.NOT_FOUND, "摘要不存在或不可查看");
        // Repeat the membership and cursor checks in the source query: a member may
        // leave, rejoin or recall a message between the two database reads.
        var items = summaries.visibleSources(summaryId, groupId, userId);
        if (items.isEmpty()) throw new ChatException(HttpStatus.NOT_FOUND, "摘要不存在或不可查看");
        return items;
    }

    private GroupDao.Membership member(long groupId, long userId) {
        if (groupId <= 0) throw new ChatException(HttpStatus.BAD_REQUEST, "讨论室编号无效");
        var membership = groups.membership(groupId, userId);
        if (membership == null) throw new ChatException(HttpStatus.FORBIDDEN, "无权访问此讨论室");
        return membership;
    }
}
