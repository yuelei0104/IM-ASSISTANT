package com.yuelei.imchat.service;

import com.yuelei.imchat.dao.NoticeDao;
import com.yuelei.imchat.exception.ChatException;
import com.yuelei.imchat.model.MentionNotice;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
public class NoticeService {
    private final NoticeDao notices;
    private final UserAccessClient access;

    public NoticeService(NoticeDao notices, UserAccessClient access) {
        this.notices = notices;
        this.access = access;
    }

    public record Page(List<MentionNotice> items, Long nextBeforeId, long unreadCount) { }
    public record Keywords(List<String> words) { }

    public Keywords keywords(String authorization) {
        return new Keywords(notices.keywords(access.requireUser(authorization)));
    }

    @Transactional
    public Keywords replaceKeywords(String authorization, List<String> words) {
        long userId = access.requireUser(authorization);
        if (words == null || words.size() > 5 || words.stream().anyMatch(word ->
                word == null || word.trim().length() < 2 || word.trim().length() > 32
                        || word.chars().anyMatch(Character::isISOControl)))
            throw new ChatException(HttpStatus.BAD_REQUEST, "最多设置 5 个关注词，每个 2 到 32 字符");
        var normalized = words.stream().map(word -> word.trim().toLowerCase(Locale.ROOT)).distinct().toList();
        if (normalized.size() != words.size())
            throw new ChatException(HttpStatus.BAD_REQUEST, "关注词不能重复");
        notices.deleteKeywords(userId);
        for (String word : normalized) notices.addKeywordWord(userId, word);
        return new Keywords(normalized);
    }

    public Page list(String authorization, Long beforeId, int limit) {
        long user = access.requireUser(authorization);
        if (beforeId != null && beforeId <= 0 || limit < 1 || limit > 50)
            throw new ChatException(HttpStatus.BAD_REQUEST, "分页参数无效");
        var rows = notices.list(user, beforeId, limit + 1);
        var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        return new Page(items, rows.size() > limit ? items.get(items.size() - 1).id() : null,
                notices.unread(user));
    }

    public void markRead(String authorization, long noticeId) {
        long user = access.requireUser(authorization);
        if (noticeId <= 0) throw new ChatException(HttpStatus.BAD_REQUEST, "通知编号无效");
        // Updating only an owned row keeps repeated reads idempotent.
        notices.markRead(user, noticeId);
    }

    public void markAllRead(String authorization) {
        notices.markAllRead(access.requireUser(authorization));
    }
}
