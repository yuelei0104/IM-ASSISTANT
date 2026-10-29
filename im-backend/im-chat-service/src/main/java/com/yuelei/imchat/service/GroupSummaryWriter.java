package com.yuelei.imchat.service;

import com.yuelei.imchat.dao.GroupDao;
import com.yuelei.imchat.dao.GroupSummaryDao;
import com.yuelei.imchat.exception.ChatException;
import com.yuelei.imchat.model.GroupMessage;
import com.yuelei.imchat.model.GroupSummary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class GroupSummaryWriter {
    private final GroupDao groups;
    private final GroupSummaryDao summaries;

    public GroupSummaryWriter(GroupDao groups, GroupSummaryDao summaries) {
        this.groups = groups;
        this.summaries = summaries;
    }

    @Transactional
    public GroupSummary save(long groupId, long userId, List<GroupMessage> messages, String content) {
        // Recall, membership changes and dissolution acquire the same group lock.
        if (groups.lockGroup(groupId) == null)
            throw new ChatException(HttpStatus.NOT_FOUND, "讨论室不存在");
        var membership = groups.membership(groupId, userId);
        if (membership == null || messages.get(0).id() <= membership.joinedAfterId())
            throw new ChatException(HttpStatus.FORBIDDEN, "无权总结加入前的消息");
        var draft = new GroupSummaryDao.Draft(groupId, userId, messages.get(0).id(),
                messages.get(messages.size() - 1).id(), content,
                messages.stream().map(GroupMessage::id).toList());
        // The SQL also rejects recalled messages and a changed join cursor.
        if (summaries.insert(draft) != 1)
            throw new ChatException(HttpStatus.FORBIDDEN, "消息已不可查看，请重新生成摘要");
        if (draft.getId() == null) throw new ChatException(HttpStatus.INTERNAL_SERVER_ERROR, "摘要保存失败");
        GroupSummary saved = summaries.visibleById(draft.getId(), groupId, userId);
        if (saved == null) throw new ChatException(HttpStatus.INTERNAL_SERVER_ERROR, "摘要读取失败");
        return saved;
    }
}
