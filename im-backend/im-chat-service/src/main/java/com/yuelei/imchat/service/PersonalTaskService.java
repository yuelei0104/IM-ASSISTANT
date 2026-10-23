package com.yuelei.imchat.service;

import com.yuelei.imchat.dao.PersonalTaskDao;
import com.yuelei.imchat.exception.ChatException;
import com.yuelei.imchat.model.PersonalTask;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PersonalTaskService {
    private final PersonalTaskDao tasks;
    private final UserAccessClient access;

    public PersonalTaskService(PersonalTaskDao tasks, UserAccessClient access) {
        this.tasks = tasks;
        this.access = access;
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

    public PersonalTask create(String auth, String rawTitle) {
        long owner = access.requireUser(auth);
        String title = rawTitle == null ? "" : rawTitle.trim();
        if (title.isEmpty() || title.length() > 100)
            throw new ChatException(HttpStatus.BAD_REQUEST, "待办标题应为 1 到 100 个字符");
        var draft = new PersonalTaskDao.Draft(owner, title);
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
