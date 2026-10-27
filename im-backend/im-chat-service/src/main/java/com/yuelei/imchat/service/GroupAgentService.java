package com.yuelei.imchat.service;

import com.yuelei.imchat.dao.GroupDao;
import com.yuelei.imchat.exception.ChatException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;

@Service
public class GroupAgentService {
    private final GroupDao groups;
    private final UserAccessClient access;
    private final GroupAgentEngine engine;

    public GroupAgentService(GroupDao groups, UserAccessClient access, GroupAgentEngine engine) {
        this.groups = groups;
        this.access = access;
        this.engine = engine;
    }

    public record Source(long id, String body) { }
    public record Answer(String answer, List<Source> sources) { }

    public Answer ask(String auth, long groupId, String rawQuestion) {
        long userId = access.requireUser(auth);
        if (groupId <= 0) throw new ChatException(HttpStatus.BAD_REQUEST, "讨论室编号无效");
        String question = rawQuestion == null ? "" : rawQuestion.trim();
        if (question.length() < 2 || question.length() > 300)
            throw new ChatException(HttpStatus.BAD_REQUEST, "问题应为 2 到 300 个字符");
        var membership = groups.membership(groupId, userId);
        if (membership == null) throw new ChatException(HttpStatus.FORBIDDEN, "无权访问此讨论室");
        // Only the caller's last 30 visible, non-recalled messages enter the model.
        var candidates = groups.history(groupId, membership.joinedAfterId(), null, 30).stream()
                .filter(m -> m.recalledAt() == null && !m.body().isBlank())
                .map(m -> new GroupAgentEngine.Candidate(m.id(),
                        m.body().substring(0, Math.min(m.body().length(), 500))))
                .toList();
        if (candidates.isEmpty()) throw new ChatException(HttpStatus.BAD_REQUEST, "群内还没有可检索的消息");
        GroupAgentEngine.Draft draft = engine.answer(question, candidates);
        if (draft == null || draft.answer() == null || draft.answer().isBlank()
                || draft.answer().length() > 2000 || draft.sourceIds() == null
                || draft.contextIds() == null)
            throw new ChatException(HttpStatus.BAD_GATEWAY, "Agent 回答无效");

        // Recheck after model inference: group removal, rejoin, recall and dissolution
        // must never return a stale answer or source text to the caller.
        var current = groups.membership(groupId, userId);
        if (current == null) throw new ChatException(HttpStatus.FORBIDDEN, "已无权访问此讨论室");
        if (draft.sourceIds().isEmpty())
            return new Answer(GroupAgentEngine.NO_EVIDENCE, List.of());
        var visibleNow = new LinkedHashMap<Long, Source>();
        // Check every message that entered the prompt or a tool result, even if
        // the model ultimately cites fewer. Recall/rejoin must invalidate the answer.
        for (Long id : draft.contextIds()) {
            if (id == null || visibleNow.containsKey(id)) continue;
            if (candidates.stream().noneMatch(candidate -> candidate.id() == id))
                throw new ChatException(HttpStatus.BAD_GATEWAY, "Agent 引用了无效的消息");
            var message = groups.messageById(groupId, id, current.joinedAfterId());
            if (message == null || message.recalledAt() != null)
                throw new ChatException(HttpStatus.FORBIDDEN, "消息已不可查看，请重新提问");
            visibleNow.put(id, new Source(id, message.body()));
        }
        var cited = new LinkedHashMap<Long, Source>();
        for (Long id : draft.sourceIds()) {
            if (id == null || cited.containsKey(id) || cited.size() >= 5) continue;
            Source source = visibleNow.get(id);
            if (source == null) throw new ChatException(HttpStatus.BAD_GATEWAY, "Agent 引用了无效的消息");
            cited.put(id, source);
        }
        if (cited.isEmpty()) throw new ChatException(HttpStatus.BAD_GATEWAY, "Agent 未找到可核对的依据");
        return new Answer(draft.answer().trim(), List.copyOf(cited.values()));
    }
}
