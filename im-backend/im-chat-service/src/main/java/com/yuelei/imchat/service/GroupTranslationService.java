package com.yuelei.imchat.service;

import com.yuelei.imchat.dao.GroupDao;
import com.yuelei.imchat.exception.ChatException;
import com.yuelei.imchat.model.GroupMessage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class GroupTranslationService {
    private final GroupDao groups;
    private final UserAccessClient access;
    private final TranslationGenerator generator;

    public GroupTranslationService(GroupDao groups, UserAccessClient access, TranslationGenerator generator) {
        this.groups = groups;
        this.access = access;
        this.generator = generator;
    }

    public record Preview(long sourceMessageId, String targetLanguage, String text) { }

    public Preview preview(String authorization, long groupId, long messageId, String targetLanguage) {
        long userId = access.requireUser(authorization);
        if (!"en".equals(targetLanguage) && !"zh".equals(targetLanguage))
            throw new ChatException(HttpStatus.BAD_REQUEST, "翻译目标仅支持 en 或 zh");
        if (groupId <= 0 || messageId <= 0)
            throw new ChatException(HttpStatus.BAD_REQUEST, "消息编号无效");
        GroupMessage source = visibleMessage(groupId, messageId, userId);
        if (source.body().isBlank() || source.body().length() > 2000)
            throw new ChatException(HttpStatus.BAD_REQUEST, "只支持 1 到 2000 字的文字或字幕翻译");
        String translated = generator.translate(source.body(), targetLanguage);
        // The model can take several seconds. Recheck after generation to avoid
        // returning content to a removed/rejoined member or after a recall.
        GroupMessage current = visibleMessage(groupId, messageId, userId);
        if (!source.body().equals(current.body()))
            throw new ChatException(HttpStatus.CONFLICT, "消息已变化，请重新翻译");
        if (translated == null || translated.isBlank() || translated.length() > 4000)
            throw new ChatException(HttpStatus.BAD_GATEWAY, "本地模型没有返回有效译文");
        return new Preview(messageId, targetLanguage, translated);
    }

    private GroupMessage visibleMessage(long groupId, long messageId, long userId) {
        var membership = groups.membership(groupId, userId);
        if (membership == null)
            throw new ChatException(HttpStatus.FORBIDDEN, "无权查看此讨论室消息");
        var message = groups.messageById(groupId, messageId, membership.joinedAfterId());
        if (message == null || message.recalledAt() != null)
            throw new ChatException(HttpStatus.NOT_FOUND, "消息不存在或不可查看");
        return message;
    }
}
