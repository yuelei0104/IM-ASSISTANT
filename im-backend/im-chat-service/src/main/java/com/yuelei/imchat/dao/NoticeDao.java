package com.yuelei.imchat.dao;

import com.yuelei.imchat.model.MentionNotice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface NoticeDao {
    record KeywordMatch(long userId, String word) { }
    int add(@Param("groupId") long groupId, @Param("messageId") long messageId,
            @Param("senderId") long senderId, @Param("recipientId") long recipientId);
    int addKeyword(@Param("groupId") long groupId, @Param("messageId") long messageId,
                   @Param("senderId") long senderId, @Param("recipientId") long recipientId);
    List<Long> recipients(@Param("messageId") long messageId);
    List<Long> noticeRecipients(@Param("messageId") long messageId);
    List<KeywordMatch> keywordsForGroup(@Param("groupId") long groupId);
    List<String> keywords(@Param("userId") long userId);
    int deleteKeywords(@Param("userId") long userId);
    int addKeywordWord(@Param("userId") long userId, @Param("word") String word);
    List<MentionNotice> list(@Param("userId") long userId,
                             @Param("beforeId") Long beforeId, @Param("limit") int limit);
    long unread(@Param("userId") long userId);
    int markRead(@Param("userId") long userId, @Param("id") long id);
    int markAllRead(@Param("userId") long userId);
    int deleteForGroup(@Param("groupId") long groupId);
    int deleteForMessage(@Param("messageId") long messageId);
}
