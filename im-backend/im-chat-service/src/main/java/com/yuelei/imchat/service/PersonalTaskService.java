package com.yuelei.imchat.service;

import com.yuelei.imchat.dao.PersonalTaskDao;
import com.yuelei.imchat.dao.GroupDao;
import com.yuelei.imchat.exception.ChatException;
import com.yuelei.imchat.model.PersonalTask;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PersonalTaskService {
    private final PersonalTaskDao tasks;
    private final UserAccessClient access;
    private final GroupDao groups;

    public PersonalTaskService(PersonalTaskDao tasks, UserAccessClient access, GroupDao groups) {
        this.tasks = tasks;
        this.access = access;
        this.groups = groups;
    }

    public record Page(List<PersonalTask> items, Long nextBeforeId, long openCount, long completedCount) { }

    public Page list(String auth, Long beforeId, int limit) {
        long owner = access.requireUser(auth);
        if (beforeId != null && beforeId <= 0 || limit < 1 || limit > 50)
            throw new ChatException(HttpStatus.BAD_REQUEST, "分页参数无效");
        var rows = tasks.list(owner, beforeId, limit + 1);
        var items = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        return new Page(items, rows.size() > limit ? items.get(items.size() - 1).id() : null,
                tasks.countOpen(owner), tasks.countCompleted(owner));
    }

    @Transactional
    public PersonalTask create(String auth, String rawTitle, Long sourceGroupId, Long sourceMessageId) {
        long owner = access.requireUser(auth);
        String title = rawTitle == null ? "" : rawTitle.trim();
        if (title.isEmpty() || title.length() > 100)
            throw new ChatException(HttpStatus.BAD_REQUEST, "待办标题应为 1 到 100 个字符");
        if ((sourceGroupId == null) != (sourceMessageId == null) ||
                sourceGroupId != null && (sourceGroupId <= 0 || sourceMessageId <= 0))
            throw new ChatException(HttpStatus.BAD_REQUEST, "消息来源参数无效");
        if (sourceGroupId != null) {
            // Use the same group-row lock as membership changes, recall and dissolution.
            if (groups.lockGroup(sourceGroupId) == null)
                throw new ChatException(HttpStatus.NOT_FOUND, "讨论室不存在");
            var membership = groups.membership(sourceGroupId, owner);
            if (membership == null)
                throw new ChatException(HttpStatus.FORBIDDEN, "无权查看消息来源");
            var source = groups.messageById(sourceGroupId, sourceMessageId, membership.joinedAfterId());
            if (source == null || source.recalledAt() != null)
                throw new ChatException(HttpStatus.NOT_FOUND, "消息来源不存在或不可查看");
        }
        var draft = new PersonalTaskDao.Draft(owner, title, sourceGroupId, sourceMessageId);
        tasks.insert(draft);
        if (draft.getId() == null) throw new ChatException(HttpStatus.INTERNAL_SERVER_ERROR, "待办保存失败");
        PersonalTask saved = tasks.owned(owner, draft.getId());
        if (saved == null) throw new ChatException(HttpStatus.INTERNAL_SERVER_ERROR, "待办读取失败");
        return saved;
    }

    public PersonalTask complete(String auth, long id, boolean completed) {
        long owner = access.requireUser(auth);
        if (id <= 0 || tasks.owned(owner, id) == null)
            throw new ChatException(HttpStatus.NOT_FOUND, "待办不存在");
        tasks.setCompleted(owner, id, completed);
        PersonalTask saved = tasks.owned(owner, id);
        if (saved == null) throw new ChatException(HttpStatus.NOT_FOUND, "待办不存在");
        return saved;
    }

    public void delete(String auth, long id) {
        long owner = access.requireUser(auth);
        if (id <= 0 || tasks.delete(owner, id) == 0)
            throw new ChatException(HttpStatus.NOT_FOUND, "待办不存在");
    }
}
